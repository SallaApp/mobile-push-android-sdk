package com.salla.push.internal.engine

/**
 * The process-wide registry: at most one engine per (app id, host tag), created under a lock. initWithContext,
 * FlushWorker, handleFirebaseMessage and the open/dismiss components all obtain the same instance, so two engines
 * can never send for the same app.
 */
internal class EngineRegistry {

    private val lock = Any()
    private val engines = LinkedHashMap<EngineKey, Engine>()

    fun get(key: EngineKey): Engine? = synchronized(lock) { engines[key] }

    /** The registered engine for [key], or a new one from [create] (registered atomically). */
    fun obtain(key: EngineKey, create: () -> Engine): Engine = synchronized(lock) {
        engines[key] ?: create().also { engines[key] = it }
    }

    fun remove(engine: Engine) {
        synchronized(lock) {
            if (engines[engine.key] === engine) engines.remove(engine.key)
        }
    }

    fun keys(): Set<EngineKey> = synchronized(lock) { HashSet(engines.keys) }

    fun all(): List<Engine> = synchronized(lock) { ArrayList(engines.values) }
}
