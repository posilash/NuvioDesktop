package com.nuvio.wayland

import com.nuvio.wayland.wpe.WpeChrome
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
import org.lwjgl.vulkan.EXTExternalMemoryDmaBuf
import org.lwjgl.vulkan.EXTImageDrmFormatModifier
import org.lwjgl.vulkan.EXTQueueFamilyForeign
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

        /**
         * Wanted, not required: importing the web chrome's dmabuf needs all
         * three, and a device without them simply keeps the chrome on its GL
         * path. Nothing here may assume a particular GPU.
         */
        private val DMABUF_EXTENSIONS = listOf(
            EXTExternalMemoryDmaBuf.VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME,
            EXTImageDrmFormatModifier.VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME,
            EXTQueueFamilyForeign.VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
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
        queue = mpvQueue,
        queueFamily = queueFamily,
        featuresChain = features2!!.address(),
        extensions = enabledExtensions,
        // Only when they share one: with a queue each there is nothing to
        // serialise, and the lock would only put them back in each other's way.
        queueLock = if (separateQueues) null else queueLock,
    )

    /** Serialises every submission to [queue]; see SharedDevice.queueLock. */
    val queueLock = java.util.concurrent.locks.ReentrantLock()

    /** Runs [block] holding [queueLock]. Every submission here goes through it. */
    private inline fun <T> withQueue(block: () -> T): T {
        if (separateQueues) return block()
        queueLock.lock()
        try { return block() } finally { queueLock.unlock() }
    }

    /** Handles for whoever else renders on this device -- mpv and Skia. */
    val featuresChain: Long get() = features2?.address() ?: 0L
    val deviceExtensions: List<String> get() = enabledExtensions

    /** Whether [importDmabuf] can be used at all on this device. */
    var dmabufImportSupported = false
        private set
    private var enabledExtensions: List<String> = DEVICE_EXTENSIONS

    private lateinit var instance: VkInstance
    private lateinit var physicalDevice: VkPhysicalDevice
    private lateinit var device: VkDevice
    private lateinit var queue: VkQueue
    /** mpv's queue: index 0, which is the only one it can be given. */
    private lateinit var mpvQueue: VkQueue
    private var queueFamily = 0
    private var queueCount = 1
    private var presentMode = KHRSurface.VK_PRESENT_MODE_FIFO_KHR
    /**
     * Whether the host paces its own commits (vsync mode 2, the default). Read
     * here rather than passed so the swapchain and the loop cannot disagree.
     */
    private val selfPaced =
        (System.getProperty("nuvio.wayland.vsync")?.toIntOrNull() ?: 2) != 1
    private var separateQueues = false
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
    /** Distinct mpv images wrapped so far -- the pool should be small and fixed. */
    val videoImageCount: Int get() = videoImages.size
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
        val modeName = when (presentMode) {
            KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE"
            KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX"
            else -> "FIFO"
        }
        println(
            "vk-present: $deviceName swapchain=${swapWidth}x$swapHeight " +
                "images=${swapImages.size} mode=$modeName selfPaced=$selfPaced",
        )
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
            // Two queues if the family has them: one for mpv, one for Skia and
            // the present. Sharing a single queue means every submission takes
            // the lock, and Skia's is a synchronous submit at frame rate --
            // measured mpv down to 11 renders/s against a 24fps stream, waiting
            // for a queue it could not get. Cross-queue ordering is already
            // handled: the video handoff rides on semaphores either way.
            queueCount = minOf(2, qprops.get(queueFamily).queueCount())
            separateQueues = queueCount > 1

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
                val dmabuf = DMABUF_EXTENSIONS.filter { it in available }
                dmabufImportSupported = dmabuf.size == DMABUF_EXTENSIONS.size
                enabledExtensions = DEVICE_EXTENSIONS + if (dmabufImportSupported) dmabuf else emptyList()
                if (!dmabufImportSupported) {
                    println(
                        "vk-dmabuf: $deviceName lacks " +
                            DMABUF_EXTENSIONS.filterNot { it in available }.joinToString() +
                            " -- the chrome stays on GL",
                    )
                }
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
                .pQueuePriorities(
                    if (separateQueues) s.floats(1.0f, 1.0f) else s.floats(1.0f),
                )
            val extNames = s.mallocPointer(enabledExtensions.size)
            for (e in enabledExtensions) extNames.put(s.ASCII(e))
            extNames.flip()
            val dci = VkDeviceCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(features2!!.address())
                .pQueueCreateInfos(qci)
                .ppEnabledExtensionNames(extNames)
            val pd = s.mallocPointer(1)
            check(vkCreateDevice(physicalDevice, dci, null, pd), "vkCreateDevice")
            device = VkDevice(pd.get(0), physicalDevice, dci)
            // Index 1 is ours when there is one; mpv keeps index 0, because
            // mpv_vulkan_queue counts from queue 0 and cannot be offset.
            vkGetDeviceQueue(device, queueFamily, if (separateQueues) 1 else 0, pd)
            queue = VkQueue(pd.get(0), device)
            vkGetDeviceQueue(device, queueFamily, 0, pd)
            mpvQueue = if (separateQueues) VkQueue(pd.get(0), device) else queue
            println(
                "vk-queue: family=$queueFamily " +
                    if (separateQueues) "skia=1 mpv=0 (no lock)" else "shared=0 (locked)",
            )
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
            // Present mode has to agree with who is pacing. vsyncMode 2 paces
            // commits onto the vblank grid itself -- that is what took this
            // host from 82.5 to 165fps -- and FIFO paces them too, by blocking
            // in acquire. Two pacers beat against each other: the cadence
            // histogram spreads from a clean 6v/7v into 0v/1v bursts with 11v
            // gaps, which is the judder. FIFO is the analogue of
            // glfwSwapInterval(1), and this path wants the analogue of 0.
            val modeCount = s.mallocInt(1)
            KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(
                physicalDevice, surface, modeCount, null,
            )
            val modes = s.mallocInt(modeCount.get(0))
            KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(
                physicalDevice, surface, modeCount, modes,
            )
            val availableModes = (0 until modes.capacity()).map { modes.get(it) }.toSet()
            val wantedModes = if (selfPaced) {
                listOf(
                    KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR,
                    KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR,
                    KHRSurface.VK_PRESENT_MODE_FIFO_KHR,
                )
            } else {
                listOf(KHRSurface.VK_PRESENT_MODE_FIFO_KHR)
            }
            presentMode = wantedModes.first { it in availableModes }
            // FIFO is the only mode guaranteed to exist, so it is the only one
            // that may be asked for with two images; the others need a third to
            // keep acquire from blocking on the one being scanned out.
            val wantImages = if (presentMode == KHRSurface.VK_PRESENT_MODE_FIFO_KHR) 2 else 3
            val minImages = maxOf(caps.minImageCount(), wantImages).let {
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
                .presentMode(presentMode)
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
    // The UI layer: what UiPipeline's published texture is on the GL path.
    //
    // Drawing the scene straight into the present target meant paying for it on
    // every present -- measured at 9-29ms of Compose per frame, which dropped
    // the host loop from 165fps to 29-67 and left mpv's frames uncollected
    // (untaken=60-87 per 5s). The scene has to be rasterized on its own cadence
    // and composited as one image, which is the whole reason UiPipeline exists.
    private var uiImage = VK_NULL_HANDLE
    private var uiMemory = VK_NULL_HANDLE
    private var uiSurface: org.jetbrains.skia.Surface? = null
    private var uiSkiaImage: org.jetbrains.skia.Image? = null

    /** Canvas the scene draws into. Null until the layer exists. */
    fun uiLayerCanvas(): org.jetbrains.skia.Canvas? = uiSurface?.canvas

    /** The layer as something the present can sample. */
    fun uiLayerImage(): org.jetbrains.skia.Image? = uiSkiaImage

    /** One scene buffer: a VkImage Skia renders into and can also sample. */
    class UiBuffer(
        val image: Long,
        val memory: Long,
        val surface: org.jetbrains.skia.Surface,
        val skia: org.jetbrains.skia.Image,
        val width: Int,
        val height: Int,
        val generation: Int,
    )

    private var uiBufferGeneration = 0

    /** A recorder of its own, because a Graphite Recorder belongs to one thread. */
    fun makeUiRecorder(): org.jetbrains.skia.gpu.graphite.Recorder? =
        graphiteContext?.makeRecorder()

    /**
     * Snap [rec] and hand it to the GPU. Locked: two recorders feed one
     * Context, and Context::insertRecording is not thread-safe.
     *
     * Not syncCpu -- the caller must not block the GPU here. The scene thread
     * would stall the queue the video runs on, and the present's own fence is
     * what orders the frames that matter.
     */
    fun submitRecorder(
        rec: org.jetbrains.skia.gpu.graphite.Recorder,
        waitSemaphore: Long = VK_NULL_HANDLE,
        signalSemaphore: Long = VK_NULL_HANDLE,
        syncCpu: Boolean = false,
    ) {
        val gc = graphiteContext ?: return
        rec.snap().use { recording ->
            graphiteLock.lock()
            try {
                gc.insertRecording(
                    org.jetbrains.skia.gpu.graphite.InsertRecordingInfo(
                        recording = recording,
                        waitSemaphores = backendSemaphores(waitSemaphore),
                        signalSemaphores = backendSemaphores(signalSemaphore),
                    ),
                )
                withQueue { gc.submit(syncCpu) }
            } finally {
                graphiteLock.unlock()
            }
        }
    }

    private val graphiteLock = java.util.concurrent.locks.ReentrantLock()

    /** Allocate a scene buffer for [rec]. Caller owns it until destroyUiBuffer. */
    fun createUiBuffer(
        rec: org.jetbrains.skia.gpu.graphite.Recorder,
        w: Int,
        h: Int,
    ): UiBuffer? {
        var image = VK_NULL_HANDLE
        var memory = VK_NULL_HANDLE
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
            if (vkCreateImage(device, ici, null, pl) != VK_SUCCESS) return null
            image = pl.get(0)
            val mr = VkMemoryRequirements.calloc(s)
            vkGetImageMemoryRequirements(device, image, mr)
            val ai = VkMemoryAllocateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(mr.size())
                .memoryTypeIndex(findMemoryType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            if (vkAllocateMemory(device, ai, null, pl) != VK_SUCCESS) {
                vkDestroyImage(device, image, null); return null
            }
            memory = pl.get(0)
            vkBindImageMemory(device, image, memory, 0)
            transitionToGeneral(image)
        }
        val info = org.jetbrains.skia.gpu.graphite.VulkanTextureInfo(
            format = org.jetbrains.skia.gpu.graphite.VulkanFormat(swapFormat),
            imageUsageFlags = org.jetbrains.skia.gpu.graphite.VulkanImageUsageFlags(
                swapUsage or VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
            ),
        )
        fun tex() = org.jetbrains.skia.gpu.graphite.BackendTexture.makeVulkan(
            width = w, height = h, textureInfo = info,
            imageLayout = VK_IMAGE_LAYOUT_GENERAL,
            queueFamilyIndex = queueFamily, imagePtr = image,
        )
        val surface = org.jetbrains.skia.Surface.wrapBackendTexture(rec, tex(), null, null)
        // Wrapped for the HOST's recorder, because that is what samples it.
        val hostRec = recorder
        val skia = if (surface != null && hostRec != null) {
            org.jetbrains.skia.Image.wrapBackendTexture(
                recorder = hostRec,
                backendTexture = tex(),
                alphaType = org.jetbrains.skia.ColorAlphaType.PREMUL,
                colorSpace = null,
                originTopLeft = true,
            )
        } else {
            null
        }
        if (surface == null || skia == null) {
            surface?.close()
            vkFreeMemory(device, memory, null)
            vkDestroyImage(device, image, null)
            return null
        }
        return UiBuffer(image, memory, surface, skia, w, h, ++uiBufferGeneration)
    }

    fun destroyUiBuffer(b: UiBuffer) {
        vkDeviceWaitIdle(device)
        b.skia.close()
        b.surface.close()
        vkFreeMemory(device, b.memory, null)
        vkDestroyImage(device, b.image, null)
    }

    private fun createUiLayer(w: Int, h: Int) {
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
            check(vkCreateImage(device, ici, null, pl), "vkCreateImage(ui layer)")
            uiImage = pl.get(0)
            val mr = VkMemoryRequirements.calloc(s)
            vkGetImageMemoryRequirements(device, uiImage, mr)
            val ai = VkMemoryAllocateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(mr.size())
                .memoryTypeIndex(findMemoryType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT))
            check(vkAllocateMemory(device, ai, null, pl), "vkAllocateMemory(ui layer)")
            uiMemory = pl.get(0)
            check(vkBindImageMemory(device, uiImage, uiMemory, 0), "vkBindImageMemory(ui layer)")
            transitionToGeneral(uiImage)
        }
        val info = org.jetbrains.skia.gpu.graphite.VulkanTextureInfo(
            format = org.jetbrains.skia.gpu.graphite.VulkanFormat(swapFormat),
            imageUsageFlags = org.jetbrains.skia.gpu.graphite.VulkanImageUsageFlags(
                swapUsage or VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
            ),
        )
        fun tex() = org.jetbrains.skia.gpu.graphite.BackendTexture.makeVulkan(
            width = w, height = h, textureInfo = info,
            imageLayout = VK_IMAGE_LAYOUT_GENERAL,
            queueFamilyIndex = queueFamily, imagePtr = uiImage,
        )
        uiSurface = org.jetbrains.skia.Surface.wrapBackendTexture(rec, tex(), null, null)
        // Same image wrapped a second time, to be read rather than written.
        uiSkiaImage = org.jetbrains.skia.Image.wrapBackendTexture(
            recorder = rec,
            backendTexture = tex(),
            alphaType = org.jetbrains.skia.ColorAlphaType.PREMUL,
            colorSpace = null,
            originTopLeft = true,
        )
        if (uiSurface == null || uiSkiaImage == null) {
            System.err.println("vk-graphite: could not build the UI layer")
        } else {
            // Load-bearing: an opaque layer can never let the video through,
            // whatever blend mode composites it.
            println(
                "vk-graphite: ui layer ${w}x$h alphaType=" +
                    "${uiSurface!!.imageInfo.colorInfo.alphaType} " +
                    "imageAlpha=${uiSkiaImage!!.imageInfo.colorInfo.alphaType}",
            )
        }
    }

    private fun destroyUiLayer() {
        uiSkiaImage?.close(); uiSkiaImage = null
        uiSurface?.close(); uiSurface = null
        if (uiImage != VK_NULL_HANDLE) { vkDestroyImage(device, uiImage, null); uiImage = VK_NULL_HANDLE }
        if (uiMemory != VK_NULL_HANDLE) { vkFreeMemory(device, uiMemory, null); uiMemory = VK_NULL_HANDLE }
    }

    /** UNDEFINED -> GENERAL, once, for an image Skia will render into. */
    private fun transitionToGeneral(image: Long) = withQueue {
        stackPush().use { s ->
            val cb = cmdBuf ?: return@use
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
                .image(image)
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
    }

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
        createUiLayer(w, h)
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
        destroyUiLayer()
        gSurface?.close()
        gSurface = null
        if (gTargetImage != VK_NULL_HANDLE) { vkDestroyImage(device, gTargetImage, null); gTargetImage = VK_NULL_HANDLE }
        if (gTargetMemory != VK_NULL_HANDLE) { vkFreeMemory(device, gTargetMemory, null); gTargetMemory = VK_NULL_HANDLE }
    }

    // One wrap per buffer, not per frame -- keyed by generation, exactly as
    // VkGlDisplayPipeline keys its GL imports. Keying on the VkImage handle
    // instead is a trap: a resize makes mpv free its buffers and allocate new
    // ones, the driver hands back the same handles, and the wrap that looks
    // like a cache hit is a Skia image over destroyed memory. That is a device
    // loss a few frames later, and it presents as the app vanishing mid-play.
    private val videoImages = HashMap<Int, org.jetbrains.skia.Image>()
    /**
     * The web chrome's frame, imported from a dmabuf and wrapped for Skia.
     * Owns its Vulkan objects; [destroyChromeImage] frees them.
     */
    class ChromeImage(
        val vkImage: Long,
        val memory: Long,
        val skia: org.jetbrains.skia.Image,
        val width: Int,
        val height: Int,
    )

    /**
     * Import a WPE dmabuf as a sampleable VkImage and wrap it for Skia.
     *
     * The GL path gets here with glEGLImageTargetTexture2DOES; with no GL
     * context on this path the same buffer has to arrive as a dmabuf, which is
     * what EGL_MESA_image_dma_buf_export produces and
     * VK_EXT_external_memory_dma_buf consumes. Still zero-copy: nothing is read
     * back or uploaded, the page's own buffer is sampled where it lies.
     *
     * The fd is consumed on success -- importing transfers ownership -- and
     * closed by the caller on failure.
     */
    fun importChromeDmabuf(d: WpeChrome.Dmabuf): ChromeImage? {
        if (!dmabufImportSupported) return null
        val rec = recorder ?: return null
        val format = drmFourccToVk(d.fourcc)
        if (format == 0) {
            if (!chromeImportFailed) {
                chromeImportFailed = true
                System.err.println(
                    "vk-dmabuf: unhandled fourcc 0x${d.fourcc.toString(16)} from the chrome",
                )
            }
            return null
        }
        stackPush().use { s ->
            // The modifier describes the tiling, so it must be given
            // explicitly along with the plane's real offset and pitch.
            val planeLayout = org.lwjgl.vulkan.VkSubresourceLayout.calloc(1, s)
            planeLayout.get(0)
                .offset(d.offset.toLong())
                .size(0L)
                .rowPitch(d.stride.toLong())
                .arrayPitch(0L)
                .depthPitch(0L)
            val modInfo = org.lwjgl.vulkan.VkImageDrmFormatModifierExplicitCreateInfoEXT.calloc(s)
                .sType(EXTImageDrmFormatModifier.VK_STRUCTURE_TYPE_IMAGE_DRM_FORMAT_MODIFIER_EXPLICIT_CREATE_INFO_EXT)
                .drmFormatModifier(d.modifier)
                .pPlaneLayouts(planeLayout)
            val extInfo = org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO)
                .pNext(modInfo.address())
                .handleTypes(EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
            val ici = VkImageCreateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .pNext(extInfo.address())
                .imageType(VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT)
                .usage(VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            ici.extent().width(d.width).height(d.height).depth(1)
            val pImage = s.mallocLong(1)
            if (vkCreateImage(device, ici, null, pImage) != VK_SUCCESS) {
                if (!chromeImportFailed) {
                    chromeImportFailed = true
                    System.err.println(
                        "vk-dmabuf: vkCreateImage rejected modifier 0x${d.modifier.toString(16)}",
                    )
                }
                return null
            }
            val vkImage = pImage.get(0)

            // Which memory types the fd is actually importable into. Guessing
            // with DEVICE_LOCAL instead is how a dmabuf import turns into a
            // driver error a frame later.
            val fdProps = org.lwjgl.vulkan.VkMemoryFdPropertiesKHR.calloc(s)
                .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR)
            if (KHRExternalMemoryFd.vkGetMemoryFdPropertiesKHR(
                    device,
                    EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT,
                    d.fd, fdProps,
                ) != VK_SUCCESS
            ) {
                vkDestroyImage(device, vkImage, null)
                return null
            }
            val mr = VkMemoryRequirements.calloc(s)
            vkGetImageMemoryRequirements(device, vkImage, mr)
            val typeBits = mr.memoryTypeBits() and fdProps.memoryTypeBits()
            if (typeBits == 0) {
                vkDestroyImage(device, vkImage, null)
                return null
            }
            val dedicated = VkMemoryDedicatedAllocateInfo.calloc(s)
                .sType(VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO)
                .image(vkImage)
            val importInfo = org.lwjgl.vulkan.VkImportMemoryFdInfoKHR.calloc(s)
                .sType(KHRExternalMemoryFd.VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR)
                .pNext(dedicated.address())
                .handleType(EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
                .fd(d.fd)
            val mai = VkMemoryAllocateInfo.calloc(s)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .pNext(importInfo.address())
                .allocationSize(mr.size())
                .memoryTypeIndex(Integer.numberOfTrailingZeros(typeBits))
            val pMem = s.mallocLong(1)
            if (vkAllocateMemory(device, mai, null, pMem) != VK_SUCCESS) {
                vkDestroyImage(device, vkImage, null)
                return null
            }
            val memory = pMem.get(0)
            if (vkBindImageMemory(device, vkImage, memory, 0) != VK_SUCCESS) {
                vkFreeMemory(device, memory, null)
                vkDestroyImage(device, vkImage, null)
                return null
            }

            val info = org.jetbrains.skia.gpu.graphite.VulkanTextureInfo(
                format = org.jetbrains.skia.gpu.graphite.VulkanFormat(format),
                imageUsageFlags = org.jetbrains.skia.gpu.graphite.VulkanImageUsageFlags(
                    VK_IMAGE_USAGE_SAMPLED_BIT,
                ),
            )
            val tex = org.jetbrains.skia.gpu.graphite.BackendTexture.makeVulkan(
                width = d.width,
                height = d.height,
                textureInfo = info,
                // UNDEFINED, and left to Skia to transition when it samples.
                // Doing it here means a submit and a queue wait per chrome
                // frame, which stalls a queue the video is running at 165fps.
                imageLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                queueFamilyIndex = queueFamily,
                imagePtr = vkImage,
            )
            val skia = org.jetbrains.skia.Image.wrapBackendTexture(
                recorder = rec,
                backendTexture = tex,
                // WebKit's output is premultiplied, same as Compose's.
                alphaType = org.jetbrains.skia.ColorAlphaType.PREMUL,
                colorSpace = null,
                originTopLeft = true,
            )
            if (skia == null) {
                vkFreeMemory(device, memory, null)
                vkDestroyImage(device, vkImage, null)
                return null
            }
            if (lastChromeSize != (d.width shl 16 or d.height)) {
                lastChromeSize = d.width shl 16 or d.height
                println(
                    "vk-dmabuf: chrome ${d.width}x${d.height} -> target ${swapWidth}x$swapHeight" +
                        (if (d.width != swapWidth || d.height != swapHeight) " (UPSCALED)" else " (1:1)") +
                        " fourcc=0x${d.fourcc.toString(16)}",
                )
            }
            return ChromeImage(vkImage, memory, skia, d.width, d.height)
        }
    }

    fun destroyChromeImage(c: ChromeImage) {
        // No wait here on purpose. This is only ever called for the frame the
        // successor replaces, and [flushGraphite] submits with syncCpu, so the
        // GPU finished with it before the frame that retires it even began.
        // A vkDeviceWaitIdle instead would stall the queue at chrome frame
        // rate -- on the queue the video is running at 165fps.
        c.skia.close()
        vkFreeMemory(device, c.memory, null)
        vkDestroyImage(device, c.vkImage, null)
    }

    /** The UI layer, 1:1 over the whole target. Plain source-over. */
    fun drawUiLayer(canvas: org.jetbrains.skia.Canvas, image: org.jetbrains.skia.Image) {
        canvas.drawImage(image, 0f, 0f)
    }

    /** True once the layer exists and can actually carry transparency. */
    val uiLayerHasAlpha: Boolean
        get() = uiSurface?.imageInfo?.colorInfo?.alphaType !=
            org.jetbrains.skia.ColorAlphaType.OPAQUE

    /** Chrome sits above everything the scene drew, so plain source-over. */
    fun drawChromeImage(canvas: org.jetbrains.skia.Canvas, c: ChromeImage, dst: org.jetbrains.skia.Rect) {
        canvas.drawImageRect(
            c.skia,
            org.jetbrains.skia.Rect.makeWH(c.width.toFloat(), c.height.toFloat()),
            dst,
            org.jetbrains.skia.SamplingMode.LINEAR,
            chromePaint,
            true,
        )
    }

    private val chromePaint = org.jetbrains.skia.Paint()
    private var chromeImportFailed = false
    private var lastChromeSize = -1

    /** DRM fourcc -> VkFormat, for the formats WPE actually hands out. */
    private fun drmFourccToVk(fourcc: Int): Int = when (fourcc) {
        // 'AR24' / 'XR24': BGRA in Vulkan's component order.
        0x34325241, 0x34325258 -> VK_FORMAT_B8G8R8A8_UNORM
        // 'AB24' / 'XB24'.
        0x34324241, 0x34324258 -> VK_FORMAT_R8G8B8A8_UNORM
        else -> 0
    }

    /** Drop wraps for buffers mpv has already thrown away. */
    private fun evictVideoWraps(current: Int) {
        val stale = videoImages.keys.filter { it < current - 3 }
        for (g in stale) videoImages.remove(g)?.close()
    }

    /** Reused, not per frame: a Paint is a native object like any other here. */
    private val videoPaint = org.jetbrains.skia.Paint().apply {
        blendMode = org.jetbrains.skia.BlendMode.DST_OVER
    }
    private val backgroundPaint = org.jetbrains.skia.Paint().apply {
        color = 0xFF000000.toInt()
        blendMode = org.jetbrains.skia.BlendMode.DST_OVER
    }

    /** Opaque black behind everything already drawn. */
    fun fillBackground(canvas: org.jetbrains.skia.Canvas) {
        canvas.drawPaint(backgroundPaint)
    }

    private val wrappedSemaphores =
        HashMap<Long, org.jetbrains.skia.gpu.graphite.BackendSemaphore>()

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
        generation: Int,
        dst: org.jetbrains.skia.Rect,
    ): Boolean {
        val rec = recorder ?: return false
        if (image == VK_NULL_HANDLE || srcWidth <= 0 || srcHeight <= 0) return false
        val wrapped = videoImages[generation] ?: run {
            evictVideoWraps(generation)
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
            videoImages[generation] = made
            made
        }
        // DST_OVER, and drawn after the scene: the player screen punches its
        // hole with BlendMode.CLEAR, so the video's place on the canvas is
        // exactly the transparent part. Drawing it first instead -- with the
        // scene on the same canvas rather than its own layer, as on GL -- means
        // the hole punch erases the video that was just drawn, which is why
        // this path showed audio and a black rectangle.
        canvas.drawImageRect(
            wrapped,
            org.jetbrains.skia.Rect.makeWH(srcWidth.toFloat(), srcHeight.toFloat()),
            dst,
            org.jetbrains.skia.SamplingMode.LINEAR,
            videoPaint,
            true,
        )
        videoDraws++
        return true
    }

    /**
     * Hand Skia's recorded work to the GPU. Runs wherever the scene lives --
     * every Skia object here belongs to that thread -- and is paired with
     * [presentGraphite], which touches only Vulkan and runs on the loop.
     */
    fun flushGraphite(waitSemaphore: Long = VK_NULL_HANDLE, signalSemaphore: Long = VK_NULL_HANDLE) {
        val gc = graphiteContext ?: return
        val rec = recorder ?: return
        if (gTargetImage == VK_NULL_HANDLE) return
        rec.snap().use { recording ->
            gc.insertRecording(
                org.jetbrains.skia.gpu.graphite.InsertRecordingInfo(
                    recording = recording,
                    waitSemaphores = backendSemaphores(waitSemaphore),
                    signalSemaphores = backendSemaphores(signalSemaphore),
                ),
            )
            // Skia submits inside here, to the queue mpv also uses.
            withQueue { gc.submit(true) }
        }
    }

    /**
     * BackendSemaphore wrappers, cached by handle. mpv rotates a fixed set, and
     * a wrapper is a native object freed from a cleaner -- building one per
     * frame is the same leak the backdrop's RenderEffect was.
     */
    private fun backendSemaphores(
        sem: Long,
    ): Array<org.jetbrains.skia.gpu.graphite.BackendSemaphore> {
        if (sem == VK_NULL_HANDLE) return emptyArray()
        return arrayOf(
            wrappedSemaphores.getOrPut(sem) {
                org.jetbrains.skia.gpu.graphite.BackendSemaphore.makeVulkan(sem)
            },
        )
    }

    /** Blit what Skia drew into a swapchain image and present it. */
    fun presentGraphite() {
        if (gTargetImage == VK_NULL_HANDLE) return
        // Skia's drawing runs first and on the same queue, so submission order
        // alone orders the blit after it. The lock is against mpv's render
        // thread, which submits to this same queue.
        withQueue { stackPush().use { s ->
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
        } }
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
