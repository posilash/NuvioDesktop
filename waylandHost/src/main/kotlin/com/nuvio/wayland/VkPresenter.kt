package com.nuvio.wayland

import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.opengl.EXTMemoryObject
import org.lwjgl.opengl.EXTMemoryObjectFD
import org.lwjgl.opengl.EXTSemaphore
import org.lwjgl.opengl.EXTSemaphoreFD
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.KHRExternalMemoryFd
import org.lwjgl.vulkan.KHRExternalSemaphoreFd
import org.lwjgl.vulkan.KHRSurface
import org.lwjgl.vulkan.KHRSwapchain
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo
import org.lwjgl.vulkan.VkExtensionProperties
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkImageBlit
import org.lwjgl.vulkan.VkImageCreateInfo
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkMemoryAllocateInfo
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo
import org.lwjgl.vulkan.VkMemoryGetFdInfoKHR
import org.lwjgl.vulkan.VkMemoryRequirements
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkPresentInfoKHR
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.VkQueueFamilyProperties
import org.lwjgl.vulkan.VkSemaphoreCreateInfo
import org.lwjgl.vulkan.VkSemaphoreGetFdInfoKHR
import org.lwjgl.vulkan.VkSubmitInfo
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR
import org.lwjgl.vulkan.VkSurfaceFormatKHR
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR
import org.jetbrains.skia.gpu.graphite.wrapBackendTexture
import java.nio.ByteBuffer

/**
 * Presents through a Vulkan swapchain while Compose keeps rendering with GL.
 *
 * Skia has no Vulkan backend on Linux -- DirectContext offers makeGL, makeMetal
 * and makeDirect3D, and the only Skiko redrawer here is LinuxOpenGLRedrawer --
 * so the scene cannot be drawn with Vulkan. It does not have to be: the window
 * is created with no client API, this owns the surface, and GL renders into an
 * exportable VkImage imported as a texture. Same interop as [VideoPipelineVk],
 * pointed the other way.
 *
 * The blit into the swapchain image is the one copy this costs. Swapchain
 * images are the driver's, created without external-memory handle types, so
 * they cannot be imported into GL and drawn into directly.
 *
 * A prototype: FIFO only, one frame in flight, and it waits out the previous
 * submit before recording the next.
 */
class VkPresenter(private val window: Long) {

    companion object {
        // Everything GL needs to see the target image and to order against it.
        private val DEVICE_EXTENSIONS = listOf(
            KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
            KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
        )

        /** GL_EXT_semaphore layout tokens; LWJGL exposes only some of these. */
        private const val GL_LAYOUT_TRANSFER_SRC_EXT = 0x9592
    }

    // mpv, through libplacebo, will not create a render context on a device
    // without these. The device is shared with it and with Skia, so it has to
    // satisfy the strictest of the three; heap-allocated because mpv holds the
    // pointer for the life of its render context.
    private var f13: org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features? = null
    private var f12: org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features? = null
    private var f11: org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features? = null
    private var features2: org.lwjgl.vulkan.VkPhysicalDeviceFeatures2? = null

    /** The device, for mpv to render on instead of creating its own. */
    fun sharedDevice() = VideoPipelineVk.SharedDevice(
        instance = instance,
        physicalDevice = physicalDevice,
        device = device,
        queue = queue,
        queueFamily = queueFamily,
        featuresChain = features2!!.address(),
        extensions = DEVICE_EXTENSIONS,
    )

    /** Handles for whoever else renders on this device -- mpv and Skia. */
    val featuresChain: Long get() = features2?.address() ?: 0L
    val deviceExtensions: List<String> get() = DEVICE_EXTENSIONS

    private lateinit var instance: VkInstance
    private lateinit var physicalDevice: VkPhysicalDevice
    private lateinit var device: VkDevice
    private lateinit var queue: VkQueue
    private var queueFamily = 0
    private var deviceName = "?"

    private var surface = VK_NULL_HANDLE
    private var swapchain = VK_NULL_HANDLE
    private var swapImages = LongArray(0)
    private var swapFormat = 0
    private var swapWidth = 0
    private var swapHeight = 0
    /** Exactly what the swapchain images were created with, so the wrap can
     *  describe them truthfully instead of guessing. */
    private var swapUsage = 0

    private var cmdPool = VK_NULL_HANDLE
    private var cmdBuf: VkCommandBuffer? = null
    private var submitFence = VK_NULL_HANDLE
    private var imageAvailable = VK_NULL_HANDLE
    // One per swapchain image, not one shared. The fence only says the command
    // buffer finished; it says nothing about vkQueuePresentKHR having consumed
    // the semaphore, so a shared one gets re-signalled while still signalled --
    // VUID-vkQueueSubmit-pSignalSemaphores-00067, and undefined behaviour after.
    private var renderFinished = LongArray(0)

    // Target: what GL draws into, what Vulkan blits from.
    private var targetImage = VK_NULL_HANDLE
    private var targetMemory = VK_NULL_HANDLE
    private var targetWidth = 0
    private var targetHeight = 0
    private var glDoneSem = VK_NULL_HANDLE // GL signals, Vulkan waits
    private var vkDoneSem = VK_NULL_HANDLE // Vulkan signals, GL waits

    private var glMemoryObject = 0
    private var glTexture = 0
    private var glFramebuffer = 0
    private var glGlDoneSem = 0
    private var glVkDoneSem = 0
    private var everSubmitted = false

    // Skia on Vulkan, sharing this device: the scene is recorded straight into
    // the acquired swapchain image, so nothing crosses to GL.
    private var graphiteContext: org.jetbrains.skia.gpu.graphite.GraphiteContext? = null
    private var recorder: org.jetbrains.skia.gpu.graphite.Recorder? = null
    private var frameSurface: org.jetbrains.skia.Surface? = null
    private var frameImageIndex = -1
    private var wrapFailed = false
    private var videoWrapFailed = false
    var videoDraws = 0L
        private set
    // Skia renders here, once, and it persists. A swapchain hands back a
    // different image each frame with undefined contents, and Compose only
    // paints when it thinks it is dirty -- so drawing straight into the
    // swapchain leaves stale frames blank. GENERAL throughout, so no layout
    // transition is ever needed: Skia can render to it and a blit can read it.
    private var gTargetImage = VK_NULL_HANDLE
    private var gTargetMemory = VK_NULL_HANDLE
    private var gSurface: org.jetbrains.skia.Surface? = null

    private val videoLog = System.getProperty("nuvio.wayland.videoLog")?.toBoolean() == true

    /** Diagnostic: skip the blit and present a flat colour. */
    private val clearOnly = System.getProperty("nuvio.wayland.vkClear")?.toBoolean() == true

    /** Raw handles, for a Skia Vulkan context sharing this device. */
    val instanceHandle: Long get() = instance.address()
    val physicalDeviceHandle: Long get() = physicalDevice.address()
    val deviceHandle: Long get() = device.address()
    val queueHandle: Long get() = queue.address()
    val graphicsQueueIndex: Int get() = queueFamily

    /** The framebuffer the frame must be rendered into. */
    val fbo: Int get() = glFramebuffer

    /** Size of that framebuffer. The surface decides it, not GLFW: a window
     *  with no client API has no framebuffer for GLFW to report. */
    val width: Int get() = targetWidth
    val height: Int get() = targetHeight

    /** Bumped whenever [fbo] becomes a different framebuffer, so the consumer
     *  knows to rewrap it. */
    var generation = 0
        private set

    private fun check(r: Int, what: String) {
        check(r == VK_SUCCESS || r == KHRSwapchain.VK_SUBOPTIMAL_KHR) { "$what failed: $r" }
    }

    fun init(width: Int, height: Int) {
        createInstanceAndDevice()
        createSurface()
        createCommandsAndSync()
        createSwapchain(width, height)
        // Match the swapchain, not the request: the surface's extent is the
        // only size that presents without scaling.
        createTarget(swapWidth, swapHeight)
        println("vk-present: $deviceName swapchain=${swapWidth}x$swapHeight images=${swapImages.size}")
    }

    private fun createInstanceAndDevice() {
        stackPush().use { s ->
            val app = VkApplicationInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(s.ASCII("nuvio-wayland"))
                .apiVersion(VK11.VK_API_VERSION_1_1)
            // Whatever GLFW says this window system needs: VK_KHR_surface plus
            // the Wayland platform extension.
            val required = glfwGetRequiredInstanceExtensions()
                ?: error("glfwGetRequiredInstanceExtensions returned null")
            val ici = VkInstanceCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(required)
            val pp = s.mallocPointer(1)
            check(vkCreateInstance(ici, null, pp), "vkCreateInstance")
            instance = VkInstance(pp.get(0), ici)

            val count = s.mallocInt(1)
            check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices")
            check(count.get(0) > 0) { "no Vulkan devices" }
            val devs = s.mallocPointer(count.get(0))
            check(vkEnumeratePhysicalDevices(instance, count, devs), "vkEnumeratePhysicalDevices")
            physicalDevice = VkPhysicalDevice(devs.get(0), instance)

            val props = VkPhysicalDeviceProperties.calloc(s)
            vkGetPhysicalDeviceProperties(physicalDevice, props)
            deviceName = props.deviceNameString()

            val qc = s.mallocInt(1)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, qc, null)
            val qprops = VkQueueFamilyProperties.calloc(qc.get(0), s)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, qc, qprops)
            // Graphics AND compute: mpv wants all three kinds from one family,
            // and this device is shared with it.
            queueFamily = (0 until qc.get(0)).firstOrNull {
                val f = qprops.get(it).queueFlags()
                f and VK_QUEUE_GRAPHICS_BIT != 0 && f and VK_QUEUE_COMPUTE_BIT != 0
            } ?: error("no graphics+compute queue family")

            val extCount = s.mallocInt(1)
            check(
                vkEnumerateDeviceExtensionProperties(physicalDevice, null as ByteBuffer?, extCount, null),
                "vkEnumerateDeviceExtensionProperties",
            )
            val eprops = VkExtensionProperties.calloc(extCount.get(0))
            try {
                check(
                    vkEnumerateDeviceExtensionProperties(physicalDevice, null as ByteBuffer?, extCount, eprops),
                    "vkEnumerateDeviceExtensionProperties",
                )
                val available = (0 until extCount.get(0)).map { eprops.get(it).extensionNameString() }.toSet()
                for (e in DEVICE_EXTENSIONS) check(e in available) { "$deviceName lacks $e" }
            } finally {
                eprops.free()
            }

            f13 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features.calloc()
                .sType(org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                .synchronization2(true)
                .dynamicRendering(true)
                .maintenance4(true)
            f12 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features.calloc()
                .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                .pNext(f13!!.address())
                .hostQueryReset(true)
                .timelineSemaphore(true)
                .bufferDeviceAddress(true)
                .descriptorIndexing(true)
                .uniformBufferStandardLayout(true)
                .shaderSubgroupExtendedTypes(true)
                .vulkanMemoryModel(true)
                .vulkanMemoryModelDeviceScope(true)
            f11 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features.calloc()
                .sType(org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES)
                .pNext(f12!!.address())
                .samplerYcbcrConversion(true)
                .storageBuffer16BitAccess(true)
            features2 = org.lwjgl.vulkan.VkPhysicalDeviceFeatures2.calloc()
                .sType(org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .pNext(f11!!.address())
            features2!!.features()
                .shaderImageGatherExtended(true)
                .shaderStorageImageReadWithoutFormat(true)
                .shaderStorageImageWriteWithoutFormat(true)

            val qci = VkDeviceQueueCreateInfo.calloc(1, s)
            qci.get(0)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(s.floats(1.0f))
            val extNames = s.mallocPointer(DEVICE_EXTENSIONS.size)
            for (e in DEVICE_EXTENSIONS) extNames.put(s.ASCII(e))
            extNames.flip()
            val dci = VkDeviceCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features2!!.address())
                .pQueueCreateInfos(qci)
                .ppEnabledExtensionNames(extNames)
            val pd = s.mallocPointer(1)
            check(vkCreateDevice(physicalDevice, dci, null, pd), "vkCreateDevice")
            device = VkDevice(pd.get(0), physicalDevice, dci)
            vkGetDeviceQueue(device, queueFamily, 0, pd)
            queue = VkQueue(pd.get(0), device)
        }
    }

    private fun createSurface() {
        stackPush().use { s ->
            val p = s.mallocLong(1)
            check(glfwCreateWindowSurface(instance, window, null, p), "glfwCreateWindowSurface")
            surface = p.get(0)
            val supported = s.mallocInt(1)
            KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, queueFamily, surface, supported)
            check(supported.get(0) == VK_TRUE) { "queue family cannot present to this surface" }
        }
    }

    private fun createCommandsAndSync() {
        stackPush().use { s ->
            val cpi = VkCommandPoolCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamily)
            val pl = s.mallocLong(1)
            check(vkCreateCommandPool(device, cpi, null, pl), "vkCreateCommandPool")
            cmdPool = pl.get(0)

            val cbi = VkCommandBufferAllocateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(cmdPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1)
            val pb = s.mallocPointer(1)
            check(vkAllocateCommandBuffers(device, cbi, pb), "vkAllocateCommandBuffers")
            cmdBuf = VkCommandBuffer(pb.get(0), device)

            val sci = VkSemaphoreCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
            check(vkCreateSemaphore(device, sci, null, pl), "vkCreateSemaphore(imageAvailable)")
            imageAvailable = pl.get(0)

            val fci = VkFenceCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            check(vkCreateFence(device, fci, null, pl), "vkCreateFence")
            submitFence = pl.get(0)
        }
    }

    private fun createSwapchain(width: Int, height: Int) {
        stackPush().use { s ->
            val caps = VkSurfaceCapabilitiesKHR.calloc(s)
            check(
                KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, caps),
                "vkGetPhysicalDeviceSurfaceCapabilitiesKHR",
            )
            val fc = s.mallocInt(1)
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, fc, null)
            val formats = VkSurfaceFormatKHR.calloc(fc.get(0), s)
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, fc, formats)
            // What the surface actually offers, in its own order of preference,
            // with -Pnuvio.wayland.vkFormat=<VkFormat> to ask for a specific
            // one. Nothing is hardcoded here: a 10-bit or float format with an
            // HDR colour space is chosen the same way when we want one.
            val wanted = System.getProperty("nuvio.wayland.vkFormat")?.toIntOrNull()
            var chosen = formats.get(0)
            if (wanted != null) {
                for (i in 0 until fc.get(0)) {
                    if (formats.get(i).format() == wanted) {
                        chosen = formats.get(i)
                        break
                    }
                }
            }
            if (videoLog) {
                val offered = (0 until fc.get(0)).joinToString(" ") {
                    "${formats.get(it).format()}/${formats.get(it).colorSpace()}"
                }
                println("vk-format: offered=[$offered] chosen=${chosen.format()}/${chosen.colorSpace()}")
            }
            swapFormat = chosen.format()

            swapWidth = if (caps.currentExtent().width() != -1) caps.currentExtent().width() else width
            swapHeight = if (caps.currentExtent().height() != -1) caps.currentExtent().height() else height
            // Ask for everything Graphite may need of a render target and keep
            // only what this surface supports. INPUT_ATTACHMENT matters: its
            // Vulkan backend reads the destination through one when blending,
            // which is why Skiko exposes that flag at all. Never request usage
            // the surface does not advertise -- that fails swapchain creation.
            swapUsage = caps.supportedUsageFlags() and (
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
                    VK_IMAGE_USAGE_SAMPLED_BIT or
                    VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT or
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT or
                    VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                )
            check(swapUsage and VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT != 0) {
                "surface cannot be rendered into: usage=${caps.supportedUsageFlags()}"
            }
            val minImages = maxOf(caps.minImageCount(), 2).let {
                if (caps.maxImageCount() > 0) minOf(it, caps.maxImageCount()) else it
            }

            val sci = VkSwapchainCreateInfoKHR.calloc(s)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(surface)
                .minImageCount(minImages)
                .imageFormat(swapFormat)
                .imageColorSpace(chosen.colorSpace())
                .imageArrayLayers(1)
                // TRANSFER_DST because the frame arrives as a blit, not a draw.
                .imageUsage(swapUsage)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(caps.currentTransform())
                .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(KHRSurface.VK_PRESENT_MODE_FIFO_KHR)
                .clipped(true)
                .oldSwapchain(VK_NULL_HANDLE)
            sci.imageExtent().width(swapWidth).height(swapHeight)

            run {
                val ww = IntArray(1); val wh = IntArray(1)
                org.lwjgl.glfw.GLFW.glfwGetWindowSize(window, ww, wh)
                val fw = IntArray(1); val fh = IntArray(1)
                org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, fw, fh)
                val sx = FloatArray(1); val sy = FloatArray(1)
                org.lwjgl.glfw.GLFW.glfwGetWindowContentScale(window, sx, sy)
                println(
                    "vk-size: caps=${caps.currentExtent().width()}x${caps.currentExtent().height()} " +
                        "min=${caps.minImageExtent().width()}x${caps.minImageExtent().height()} " +
                        "max=${caps.maxImageExtent().width()}x${caps.maxImageExtent().height()} " +
                        "win=${ww[0]}x${wh[0]} fb=${fw[0]}x${fh[0]} contentScale=${sx[0]} " +
                        "-> swap=${swapWidth}x$swapHeight",
                )
            }
            val pl = s.mallocLong(1)
            check(KHRSwapchain.vkCreateSwapchainKHR(device, sci, null, pl), "vkCreateSwapchainKHR")
            swapchain = pl.get(0)

            val ic = s.mallocInt(1)
            KHRSwapchain.vkGetSwapchainImagesKHR(device, swapchain, ic, null)
            val imgs = s.mallocLong(ic.get(0))
            KHRSwapchain.vkGetSwapchainImagesKHR(device, swapchain, ic, imgs)
            swapImages = LongArray(ic.get(0)) { imgs.get(it) }

            val sci2 = VkSemaphoreCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
            val ps = s.mallocLong(1)
            renderFinished = LongArray(swapImages.size) {
                check(vkCreateSemaphore(device, sci2, null, ps), "vkCreateSemaphore(renderFinished)")
                ps.get(0)
            }
        }
    }

    private fun findMemoryType(typeBits: Int, wanted: Int): Int {
        stackPush().use { s ->
            val mp = VkPhysicalDeviceMemoryProperties.calloc(s)
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, mp)
            for (i in 0 until mp.memoryTypeCount()) {
                if (typeBits and (1 shl i) == 0) continue
                if (mp.memoryTypes(i).propertyFlags() and wanted == wanted) return i
            }
        }
        error("no memory type for $typeBits/$wanted")
    }

    /** The image GL draws into, exported and imported as a texture + FBO. */
    private fun createTarget(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
        var memoryFd = -1
        var glDoneFd = -1
        var vkDoneFd = -1
        var allocationSize = 0L
        stackPush().use { s ->
            val ext = VkExternalMemoryImageCreateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
            val ici = VkImageCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(ext.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_SRC_BIT or VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            ici.extent().width(width).height(height).depth(1)
            val pl = s.mallocLong(1)
            check(vkCreateImage(device, ici, null, pl), "vkCreateImage(target)")
            targetImage = pl.get(0)

            val mr = VkMemoryRequirements.calloc(s)
            vkGetImageMemoryRequirements(device, targetImage, mr)
            val dedicated = VkMemoryDedicatedAllocateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                .image(targetImage)
            val export = VkExportMemoryAllocateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO)
                .pNext(dedicated.address())
                .handleTypes(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
            val ai = VkMemoryAllocateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(export.address())
                .allocationSize(mr.size())
                .memoryTypeIndex(findMemoryType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            check(vkAllocateMemory(device, ai, null, pl), "vkAllocateMemory(target)")
            targetMemory = pl.get(0)
            allocationSize = mr.size()
            check(vkBindImageMemory(device, targetImage, targetMemory, 0), "vkBindImageMemory(target)")

            val pFd = s.mallocInt(1)
            val mgfi = VkMemoryGetFdInfoKHR.calloc(s)
                .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR)
                .memory(targetMemory)
                .handleType(VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT)
            check(KHRExternalMemoryFd.vkGetMemoryFdKHR(device, mgfi, pFd), "vkGetMemoryFdKHR")
            memoryFd = pFd.get(0)

            val sExport = VkExportSemaphoreCreateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO)
                .handleTypes(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT)
            val sci = VkSemaphoreCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                .pNext(sExport.address())
            check(vkCreateSemaphore(device, sci, null, pl), "vkCreateSemaphore(glDone)")
            glDoneSem = pl.get(0)
            check(vkCreateSemaphore(device, sci, null, pl), "vkCreateSemaphore(vkDone)")
            vkDoneSem = pl.get(0)

            val sgfi = VkSemaphoreGetFdInfoKHR.calloc(s)
                .sType(KHRExternalSemaphoreFd.VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR)
                .handleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT)
            sgfi.semaphore(glDoneSem)
            check(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device, sgfi, pFd), "vkGetSemaphoreFdKHR(glDone)")
            glDoneFd = pFd.get(0)
            sgfi.semaphore(vkDoneSem)
            check(KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(device, sgfi, pFd), "vkGetSemaphoreFdKHR(vkDone)")
            vkDoneFd = pFd.get(0)
        }

        // GL side. The fds are consumed by the driver on import.
        glMemoryObject = EXTMemoryObject.glCreateMemoryObjectsEXT()
        EXTMemoryObjectFD.glImportMemoryFdEXT(
            glMemoryObject, allocationSize, EXTMemoryObjectFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, memoryFd,
        )
        glTexture = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexture)
        GL11.glTexParameteri(
            GL11.GL_TEXTURE_2D, EXTMemoryObject.GL_TEXTURE_TILING_EXT, EXTMemoryObject.GL_OPTIMAL_TILING_EXT,
        )
        EXTMemoryObject.glTexStorageMem2DEXT(
            GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, width, height, glMemoryObject, 0L,
        )
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)

        glFramebuffer = GL30.glGenFramebuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, glFramebuffer)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glTexture, 0,
        )
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        check(status == GL30.GL_FRAMEBUFFER_COMPLETE) { "target FBO incomplete: $status" }

        glGlDoneSem = EXTSemaphore.glGenSemaphoresEXT()
        EXTSemaphoreFD.glImportSemaphoreFdEXT(glGlDoneSem, EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, glDoneFd)
        glVkDoneSem = EXTSemaphore.glGenSemaphoresEXT()
        EXTSemaphoreFD.glImportSemaphoreFdEXT(glVkDoneSem, EXTSemaphoreFD.GL_HANDLE_TYPE_OPAQUE_FD_EXT, vkDoneFd)
    }

    /**
     * Hand the frame GL has just drawn to the compositor. Called with the GL
     * context current, having rendered into [fbo].
     */
    fun present() {
        val cb = cmdBuf ?: return
        // Poll rather than wait to be told: see windowSize().
        val (ww, wh) = windowSize()
        if (ww > 0 && wh > 0 && (ww != swapWidth || wh != swapHeight)) {
            rebuild()
            return
        }
        stackPush().use { s ->
            if (everSubmitted) {
                vkWaitForFences(device, submitFence, true, Long.MAX_VALUE)
            }

            val pi = s.mallocInt(1)
            // Acquire BEFORE signalling anything GL-side. A GL semaphore signal
            // with no matching wait desynchronises the pair for good, and the
            // next wait then blocks forever -- so nothing may be signalled on a
            // path that can still bail out.
            val acquired = KHRSwapchain.vkAcquireNextImageKHR(
                device, swapchain, Long.MAX_VALUE, imageAvailable, VK_NULL_HANDLE, pi,
            )
            if (acquired == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR ||
                acquired == KHRSwapchain.VK_SUBOPTIMAL_KHR
            ) {
                rebuild()
                return
            }
            check(acquired, "vkAcquireNextImageKHR")
            vkResetFences(device, submitFence)


            // Committed now: order the blit after GL's drawing and leave the
            // image in the layout the blit wants.
            EXTSemaphore.glSignalSemaphoreEXT(
                glGlDoneSem, IntArray(0), intArrayOf(glTexture), intArrayOf(GL_LAYOUT_TRANSFER_SRC_EXT),
            )
            GL11.glFlush()
            val image = swapImages[pi.get(0)]

            vkResetCommandBuffer(cb, 0)
            val bi = VkCommandBufferBeginInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            check(vkBeginCommandBuffer(cb, bi), "vkBeginCommandBuffer")

            val toDst = VkImageMemoryBarrier.calloc(1, s)
            toDst.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .subresourceRange {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(1).baseArrayLayer(0).layerCount(1)
                }
            vkCmdPipelineBarrier(
                cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, toDst,
            )

            // GL's framebuffer origin is bottom-left and Vulkan's is top-left,
            // so the source rows are read in reverse.
            val blit = VkImageBlit.calloc(1, s)
            blit.get(0).apply {
                srcSubresource {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                }
                dstSubresource {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                }
                srcOffsets(0).set(0, targetHeight, 0)
                srcOffsets(1).set(targetWidth, 0, 1)
                dstOffsets(0).set(0, 0, 0)
                dstOffsets(1).set(swapWidth, swapHeight, 1)
            }
            if (clearOnly) {
                // Diagnostic: present a colour Vulkan produces itself, so a
                // black window means the presentation is at fault rather than
                // anything GL handed over.
                val cc = org.lwjgl.vulkan.VkClearColorValue.calloc(s)
                cc.float32(0, 1.0f).float32(1, 0.0f).float32(2, 1.0f).float32(3, 1.0f)
                val range = org.lwjgl.vulkan.VkImageSubresourceRange.calloc(1, s)
                range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
                vkCmdClearColorImage(cb, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, cc, range)
            } else {
                vkCmdBlitImage(
                    cb, targetImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR,
                )
            }

            val toPresent = VkImageMemoryBarrier.calloc(1, s)
            toPresent.get(0)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(0)
                .subresourceRange {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(1).baseArrayLayer(0).layerCount(1)
                }
            vkCmdPipelineBarrier(
                cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0, null, null, toPresent,
            )
            check(vkEndCommandBuffer(cb), "vkEndCommandBuffer")

            val si = VkSubmitInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(2)
                .pWaitSemaphores(s.longs(glDoneSem, imageAvailable))
                .pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT))
                .pCommandBuffers(s.pointers(cb))
                .pSignalSemaphores(s.longs(renderFinished[pi.get(0)], vkDoneSem))
            check(vkQueueSubmit(queue, si, submitFence), "vkQueueSubmit")
            everSubmitted = true

            val present = VkPresentInfoKHR.calloc(s)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(s.longs(renderFinished[pi.get(0)]))
                .swapchainCount(1)
                .pSwapchains(s.longs(swapchain))
                .pImageIndices(s.ints(pi.get(0)))
            val r = KHRSwapchain.vkQueuePresentKHR(queue, present)
            val stale = r == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || r == KHRSwapchain.VK_SUBOPTIMAL_KHR
            if (!stale) check(r, "vkQueuePresentKHR")

            // The submit signalled vkDone, so this wait has its partner and GL
            // will not draw into the image before the blit has read it. Skip it
            // when rebuilding: that replaces the semaphore with a fresh one
            // nothing has signalled, and waiting on that never returns.
            if (stale) rebuild() else {
                EXTSemaphore.glWaitSemaphoreEXT(
                    glVkDoneSem, IntArray(0), intArrayOf(glTexture), intArrayOf(GL_LAYOUT_TRANSFER_SRC_EXT),
                )
            }
        }
    }

    /**
     * Rebuild against the surface's current size. Called when the swapchain
     * reports itself out of date, which is the only reliable notice of a resize
     * here: the window has no framebuffer for GLFW to measure.
     */
    private fun createGraphiteTarget(w: Int, h: Int) {
        val rec = recorder ?: return
        stackPush().use { s ->
            val ici = VkImageCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(swapFormat)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(swapUsage or VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            ici.extent().width(w).height(h).depth(1)
            val pl = s.mallocLong(1)
            check(vkCreateImage(device, ici, null, pl), "vkCreateImage(graphite target)")
            gTargetImage = pl.get(0)
            val mr = VkMemoryRequirements.calloc(s)
            vkGetImageMemoryRequirements(device, gTargetImage, mr)
            val ai = VkMemoryAllocateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(mr.size())
                .memoryTypeIndex(findMemoryType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            check(vkAllocateMemory(device, ai, null, pl), "vkAllocateMemory(graphite target)")
            gTargetMemory = pl.get(0)
            check(vkBindImageMemory(device, gTargetImage, gTargetMemory, 0), "vkBindImageMemory")

            // Once into GENERAL, and it stays there for good.
            val cb = cmdBuf ?: return
            vkResetCommandBuffer(cb, 0)
            val bi = VkCommandBufferBeginInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            vkBeginCommandBuffer(cb, bi)
            val b = VkImageMemoryBarrier.calloc(1, s)
            b.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(gTargetImage)
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                .subresourceRange {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(1).baseArrayLayer(0).layerCount(1)
                }
            vkCmdPipelineBarrier(
                cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, b,
            )
            vkEndCommandBuffer(cb)
            val si = VkSubmitInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(s.pointers(cb))
            vkQueueSubmit(queue, si, VK_NULL_HANDLE)
            vkQueueWaitIdle(queue)
        }

        val info = org.jetbrains.skia.gpu.graphite.VulkanTextureInfo(
            format = org.jetbrains.skia.gpu.graphite.VulkanFormat(swapFormat),
            imageUsageFlags = org.jetbrains.skia.gpu.graphite.VulkanImageUsageFlags(
                swapUsage or VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
            ),
        )
        val tex = org.jetbrains.skia.gpu.graphite.BackendTexture.makeVulkan(
            width = w, height = h, textureInfo = info,
            imageLayout = VK_IMAGE_LAYOUT_GENERAL,
            queueFamilyIndex = queueFamily, imagePtr = gTargetImage,
        )
        gSurface = org.jetbrains.skia.Surface.wrapBackendTexture(rec, tex, null, null)
        if (gSurface == null) {
            System.err.println("vk-graphite: Skia refused the render target (format=$swapFormat)")
        }
    }

    /** Bring up Skia's Vulkan backend on this device. */
    fun initGraphite() {
        val gc = org.jetbrains.skia.gpu.graphite.GraphiteContext.makeVulkan(
            instance.address(), physicalDevice.address(), device.address(),
            queue.address(), queueFamily, VK11.VK_API_VERSION_1_1,
        )
        graphiteContext = gc
        recorder = gc.makeRecorder()
        createGraphiteTarget(swapWidth, swapHeight)
        println("vk-graphite: Skia is rendering with Vulkan on $deviceName")
    }

    /**
     * Acquire the next image and wrap it as a Skia surface. The canvas returned
     * draws directly into what will be presented -- no intermediate image, no
     * export, no blit. Null means the frame should be skipped.
     */
    fun beginFrameGraphite(): org.jetbrains.skia.Canvas? {
        val (ww, wh) = windowSize()
        if (ww > 0 && wh > 0 && (ww != swapWidth || wh != swapHeight)) {
            rebuild()
            destroyGraphiteTarget()
            createGraphiteTarget(swapWidth, swapHeight)
            return null
        }
        return gSurface?.canvas
    }

    private fun destroyGraphiteTarget() {
        vkDeviceWaitIdle(device)
        gSurface?.close()
        gSurface = null
        if (gTargetImage != VK_NULL_HANDLE) { vkDestroyImage(device, gTargetImage, null); gTargetImage = VK_NULL_HANDLE }
        if (gTargetMemory != VK_NULL_HANDLE) { vkFreeMemory(device, gTargetMemory, null); gTargetMemory = VK_NULL_HANDLE }
    }

    // One wrap per image, not per frame. The pipeline rotates a small fixed
    // set, so keying on the handle reuses them for the life of the buffers.
    private val videoImages = HashMap<Long, org.jetbrains.skia.Image>()

    /**
     * Draw one of mpv's frames onto the canvas.
     *
     * The image is mpv's own, on this same device, so it is sampled where it
     * lies -- nothing is exported, imported or copied. Wrapping it needs
     * Image.wrapBackendTexture, which Skiko does not bind; ours does.
     */
    fun drawVideoFrame(
        canvas: org.jetbrains.skia.Canvas,
        image: Long,
        srcWidth: Int,
        srcHeight: Int,
        dst: org.jetbrains.skia.Rect,
    ): Boolean {
        val rec = recorder ?: return false
        if (image == VK_NULL_HANDLE || srcWidth <= 0 || srcHeight <= 0) return false
        val wrapped = videoImages[image] ?: run {
            val info = org.jetbrains.skia.gpu.graphite.VulkanTextureInfo(
                format = org.jetbrains.skia.gpu.graphite.VulkanFormat(VideoPipelineVk.FORMAT),
                imageUsageFlags = org.jetbrains.skia.gpu.graphite.VulkanImageUsageFlags(
                    VideoPipelineVk.USAGE,
                ),
            )
            val tex = org.jetbrains.skia.gpu.graphite.BackendTexture.makeVulkan(
                width = srcWidth,
                height = srcHeight,
                textureInfo = info,
                // mpv leaves its target images in GENERAL.
                imageLayout = VK_IMAGE_LAYOUT_GENERAL,
                queueFamilyIndex = queueFamily,
                imagePtr = image,
            )
            val made = org.jetbrains.skia.Image.wrapBackendTexture(
                recorder = rec,
                backendTexture = tex,
                alphaType = org.jetbrains.skia.ColorAlphaType.OPAQUE,
                colorSpace = null,
                originTopLeft = true,
            )
            if (made == null) {
                if (!videoWrapFailed) {
                    videoWrapFailed = true
                    System.err.println("vk-graphite: could not wrap mpv's frame as an image")
                }
                return false
            }
            videoImages[image] = made
            made
        }
        canvas.drawImageRect(wrapped, dst)
        videoDraws++
        return true
    }

    /**
     * Hand Skia's recorded work to the GPU. Runs wherever the scene lives --
     * every Skia object here belongs to that thread -- and is paired with
     * [presentGraphite], which touches only Vulkan and runs on the loop.
     */
    fun flushGraphite() {
        val gc = graphiteContext ?: return
        val rec = recorder ?: return
        if (gTargetImage == VK_NULL_HANDLE) return
        rec.snap().use { recording ->
            gc.insertRecording(recording)
            gc.submit(true)
        }
    }

    /** Blit what Skia drew into a swapchain image and present it. */
    fun presentGraphite() {
        if (gTargetImage == VK_NULL_HANDLE) return
        // Skia's drawing runs first and on the same queue, so submission order
        // alone orders the blit after it.
        stackPush().use { s ->
            if (everSubmitted) vkWaitForFences(device, submitFence, true, Long.MAX_VALUE)
            val pi = s.mallocInt(1)
            val acquired = KHRSwapchain.vkAcquireNextImageKHR(
                device, swapchain, Long.MAX_VALUE, imageAvailable, VK_NULL_HANDLE, pi,
            )
            if (acquired == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR ||
                acquired == KHRSwapchain.VK_SUBOPTIMAL_KHR
            ) {
                rebuild(); destroyGraphiteTarget(); createGraphiteTarget(swapWidth, swapHeight)
                return
            }
            check(acquired, "vkAcquireNextImageKHR")
            vkResetFences(device, submitFence)
            val idx = pi.get(0)
            val image = swapImages[idx]

            val cb = cmdBuf ?: return
            vkResetCommandBuffer(cb, 0)
            val bi = VkCommandBufferBeginInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            check(vkBeginCommandBuffer(cb, bi), "vkBeginCommandBuffer")

            val toDst = VkImageMemoryBarrier.calloc(1, s)
            toDst.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .subresourceRange {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(1).baseArrayLayer(0).layerCount(1)
                }
            vkCmdPipelineBarrier(
                cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, toDst,
            )

            // Both are top-left origin here, so no flip.
            val blit = VkImageBlit.calloc(1, s)
            blit.get(0).apply {
                srcSubresource {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                }
                dstSubresource {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1)
                }
                srcOffsets(0).set(0, 0, 0)
                srcOffsets(1).set(swapWidth, swapHeight, 1)
                dstOffsets(0).set(0, 0, 0)
                dstOffsets(1).set(swapWidth, swapHeight, 1)
            }
            vkCmdBlitImage(
                cb, gTargetImage, VK_IMAGE_LAYOUT_GENERAL,
                image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST,
            )

            val toPresent = VkImageMemoryBarrier.calloc(1, s)
            toPresent.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(0)
                .subresourceRange {
                    it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0)
                        .levelCount(1).baseArrayLayer(0).layerCount(1)
                }
            vkCmdPipelineBarrier(
                cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0, null, null, toPresent,
            )
            check(vkEndCommandBuffer(cb), "vkEndCommandBuffer")

            val si = VkSubmitInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(1)
                .pWaitSemaphores(s.longs(imageAvailable))
                .pWaitDstStageMask(s.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                .pCommandBuffers(s.pointers(cb))
                .pSignalSemaphores(s.longs(renderFinished[idx]))
            check(vkQueueSubmit(queue, si, submitFence), "vkQueueSubmit")
            everSubmitted = true

            val present = VkPresentInfoKHR.calloc(s)
                .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(s.longs(renderFinished[idx]))
                .swapchainCount(1)
                .pSwapchains(s.longs(swapchain))
                .pImageIndices(s.ints(idx))
            val r = KHRSwapchain.vkQueuePresentKHR(queue, present)
            if (r == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || r == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                rebuild(); destroyGraphiteTarget(); createGraphiteTarget(swapWidth, swapHeight)
            } else {
                check(r, "vkQueuePresentKHR")
            }
        }
    }

    /**
     * The surface's size. This is the only notice of a resize we get: with a
     * client-chosen extent (caps reports -1) the buffer we present *is* the
     * surface size, so the swapchain never goes out of date on its own -- going
     * fullscreen just stretched the old one across the screen.
     */
    private fun windowSize(): Pair<Int, Int> {
        val w = IntArray(1)
        val h = IntArray(1)
        // Framebuffer, not window: on a fractionally scaled output these differ
        // (1272x1380 against 1018x1104 at 1.25), and the buffer has to carry the
        // physical pixels or the compositor upscales a soft one. GLFW has already
        // set the viewport that maps it back to the logical size.
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, w, h)
        return w[0] to h[0]
    }

    fun rebuild() {
        val (ww, wh) = windowSize()
        if (ww <= 0 || wh <= 0) return
        vkDeviceWaitIdle(device)
        destroyTarget()
        for (sem in renderFinished) vkDestroySemaphore(device, sem, null)
        renderFinished = LongArray(0)
        KHRSwapchain.vkDestroySwapchainKHR(device, swapchain, null)
        swapchain = VK_NULL_HANDLE
        createSwapchain(ww, wh)
        // The target matches the swapchain, so the blit is 1:1 and the scene is
        // laid out at the size actually being presented.
        createTarget(swapWidth, swapHeight)
        everSubmitted = false
        generation++
    }

    private fun destroyTarget() {
        if (glFramebuffer != 0) { GL30.glDeleteFramebuffers(glFramebuffer); glFramebuffer = 0 }
        if (glTexture != 0) { GL11.glDeleteTextures(glTexture); glTexture = 0 }
        if (glMemoryObject != 0) { EXTMemoryObject.glDeleteMemoryObjectsEXT(glMemoryObject); glMemoryObject = 0 }
        if (glGlDoneSem != 0) { EXTSemaphore.glDeleteSemaphoresEXT(glGlDoneSem); glGlDoneSem = 0 }
        if (glVkDoneSem != 0) { EXTSemaphore.glDeleteSemaphoresEXT(glVkDoneSem); glVkDoneSem = 0 }
        if (glDoneSem != VK_NULL_HANDLE) { vkDestroySemaphore(device, glDoneSem, null); glDoneSem = VK_NULL_HANDLE }
        if (vkDoneSem != VK_NULL_HANDLE) { vkDestroySemaphore(device, vkDoneSem, null); vkDoneSem = VK_NULL_HANDLE }
        if (targetImage != VK_NULL_HANDLE) { vkDestroyImage(device, targetImage, null); targetImage = VK_NULL_HANDLE }
        if (targetMemory != VK_NULL_HANDLE) { vkFreeMemory(device, targetMemory, null); targetMemory = VK_NULL_HANDLE }
    }

    fun destroy() {
        vkDeviceWaitIdle(device)
        destroyTarget()
        if (swapchain != VK_NULL_HANDLE) KHRSwapchain.vkDestroySwapchainKHR(device, swapchain, null)
        if (submitFence != VK_NULL_HANDLE) vkDestroyFence(device, submitFence, null)
        if (imageAvailable != VK_NULL_HANDLE) vkDestroySemaphore(device, imageAvailable, null)
        for (sem in renderFinished) vkDestroySemaphore(device, sem, null)
        if (cmdPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, cmdPool, null)
        vkDestroyDevice(device, null)
        if (surface != VK_NULL_HANDLE) KHRSurface.vkDestroySurfaceKHR(instance, surface, null)
        vkDestroyInstance(instance, null)
    }
}
