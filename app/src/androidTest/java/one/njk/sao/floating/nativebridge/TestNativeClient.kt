package one.njk.sao.floating.nativebridge

/** Synthetic clients publish on subscription/events, never through a polling timer. */
abstract class TestNativeClient : INativeStickerClient.Stub() {
    private var observer: INativeStateObserver? = null
    override fun observe(observer: INativeStateObserver?) {
        this.observer = observer
        publishState()
    }
    protected fun publishState() { observer?.changed(snapshot(false)) }
}
