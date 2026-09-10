package com.thinkspace.pdfengine.logging

import android.util.Log

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    NONE
}

/**
 * Structured logging contract for the PDF Document Engine.
 * Allows logging to be enabled, disabled, or injected without spamming Logcat.
 * Never logs sensitive document content by default.
 */
interface PdfLogger {
    fun debug(tag: String, message: () -> String)
    fun info(tag: String, message: () -> String)
    fun warn(tag: String, message: () -> String, throwable: Throwable? = null)
    fun error(tag: String, message: () -> String, throwable: Throwable? = null)
}

/**
 * Default Android-aware logger with configurable log level.
 */
class DefaultPdfLogger(
    private var minLevel: LogLevel = LogLevel.WARN,
    private val tagPrefix: String = "PdfEngine"
) : PdfLogger {

    fun setLogLevel(level: LogLevel) {
        this.minLevel = level
    }

    override fun debug(tag: String, message: () -> String) {
        if (minLevel <= LogLevel.DEBUG) {
            Log.d("$tagPrefix:$tag", message())
        }
    }

    override fun info(tag: String, message: () -> String) {
        if (minLevel <= LogLevel.INFO) {
            Log.i("$tagPrefix:$tag", message())
        }
    }

    override fun warn(tag: String, message: () -> String, throwable: Throwable?) {
        if (minLevel <= LogLevel.WARN) {
            val formattedTag = "$tagPrefix:$tag"
            if (throwable != null) {
                Log.w(formattedTag, message(), throwable)
            } else {
                Log.w(formattedTag, message())
            }
        }
    }

    override fun error(tag: String, message: () -> String, throwable: Throwable?) {
        if (minLevel <= LogLevel.ERROR) {
            val formattedTag = "$tagPrefix:$tag"
            if (throwable != null) {
                Log.e(formattedTag, message(), throwable)
            } else {
                Log.e(formattedTag, message())
            }
        }
    }
}

/**
 * Silent logger for zero overhead or test environments.
 */
object NoOpPdfLogger : PdfLogger {
    override fun debug(tag: String, message: () -> String) {}
    override fun info(tag: String, message: () -> String) {}
    override fun warn(tag: String, message: () -> String, throwable: Throwable?) {}
    override fun error(tag: String, message: () -> String, throwable: Throwable?) {}
}
