package com.nuvio.wayland

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import kotlin.coroutines.CoroutineContext

/**
 * Makes `Dispatchers.Main` mean "the thread that owns the Compose scene".
 *
 * This exists because of a hard Compose-side constraint, found the expensive
 * way. `androidx.lifecycle.compose.rememberLifecycleOwner` -- which the app
 * reaches through navigation3 and every `collectAsStateWithLifecycle` -- builds
 * its ComposeLifecycleOwner with the ENFORCING `LifecycleRegistry(owner)`
 * constructor, not `createUnsafe`. That registry's `setCurrentState` asserts
 * `Dispatchers.Main.immediate.isDispatchNeeded() == false`, and it is called
 * during the composition's apply phase, i.e. from inside `ComposeScene.render`.
 *
 * So rasterizing Compose on any thread that is not the main dispatcher's throws
 * `IllegalStateException: Method setCurrentState must be called on the main
 * thread` out of `render()`. Observed symptom: the app draws its splash once
 * and freezes there forever, because that exception killed the UI thread.
 *
 * On desktop `Dispatchers.Main` is otherwise kotlinx-coroutines-swing's EDT
 * dispatcher. Overriding it through the same `MainDispatcherFactory` service
 * point that coroutines-swing/-android/-javafx use is the supported way to say
 * "the UI thread is over here now": lifecycle's assertion then passes, and
 * everything the app posts to `Dispatchers.Main` lands on the thread that
 * actually owns the UI, which is what that dispatcher is supposed to mean.
 *
 * Falls back to the EDT whenever no UI thread is installed -- including the
 * whole `-Pnuvio.wayland.uiThread=false` path, where it is behaviourally
 * identical to the Swing dispatcher it displaces.
 */
@OptIn(InternalCoroutinesApi::class)
class NuvioMainDispatcherFactory : MainDispatcherFactory {
    // coroutines-swing's factory is priority 0; anything higher wins the
    // MainDispatcherLoader election.
    override val loadPriority: Int get() = 100

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher =
        NuvioMainDispatcher
}

/** The dispatcher [NuvioMainDispatcherFactory] installs as `Dispatchers.Main`. */
object NuvioMainDispatcher : MainCoroutineDispatcher() {

    /** Set by [UiPipeline] on its own thread, before the scene is built. */
    @Volatile
    internal var uiThread: Thread? = null

    /** Set by [UiPipeline]; posts onto the UI thread's task queue. */
    @Volatile
    internal var post: ((Runnable) -> Unit)? = null

    override val immediate: MainCoroutineDispatcher get() = Immediate

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val p = post
        if (p != null) p(block) else java.awt.EventQueue.invokeLater(block)
    }

    override fun toString(): String = "Dispatchers.Main[nuvio-ui]"

    /**
     * The `.immediate` half. Its `isDispatchNeeded` is the single question
     * androidx.lifecycle asks to decide "am I on the main thread?", so this is
     * the method that makes the whole arrangement work.
     */
    private object Immediate : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this

        override fun isDispatchNeeded(context: CoroutineContext): Boolean {
            val t = uiThread
            return if (t != null) {
                Thread.currentThread() !== t
            } else {
                !java.awt.EventQueue.isDispatchThread()
            }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) =
            NuvioMainDispatcher.dispatch(context, block)

        override fun toString(): String = "Dispatchers.Main.immediate[nuvio-ui]"
    }
}

/** Plain dispatcher over the same queue, for the scene's coroutineContext. */
internal class UiThreadDispatcher(private val post: (Runnable) -> Unit) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = post(block)
}
