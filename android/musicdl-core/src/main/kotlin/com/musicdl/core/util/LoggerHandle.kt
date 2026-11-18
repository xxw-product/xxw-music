package com.musicdl.core.util

/** Port of `musicdl/modules/utils/logger.py::LoggerHandle` (print-free variant). */
interface LoggerHandle {
    fun info(message: String)
    fun warning(message: String)
    fun error(message: String)

    companion object {
        val SILENT: LoggerHandle = object : LoggerHandle {
            override fun info(message: String) = Unit
            override fun warning(message: String) = Unit
            override fun error(message: String) = Unit
        }
    }
}

/** Default logger that forwards to a sink the host app controls (Logcat, UI, file). */
class SinkLoggerHandle(private val sink: (Level, String) -> Unit) : LoggerHandle {
    enum class Level { INFO, WARNING, ERROR }

    override fun info(message: String) = sink(Level.INFO, message)
    override fun warning(message: String) = sink(Level.WARNING, message)
    override fun error(message: String) = sink(Level.ERROR, message)
}
