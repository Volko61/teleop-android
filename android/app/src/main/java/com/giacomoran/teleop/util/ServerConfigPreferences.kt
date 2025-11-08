package com.giacomoran.teleop.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Encapsulates SharedPreferences operations for server configuration.
 * Provides a clean interface to save and retrieve the last used IP address and port.
 */
class ServerConfigPreferences private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "server_config_prefs"
        private const val KEY_IP_ADDRESS = "ip_address"
        private const val KEY_PORT = "port"

        private const val DEFAULT_IP_ADDRESS = "192.168.1.100"
        private const val DEFAULT_PORT = "4443"

        @Volatile
        private var instance: ServerConfigPreferences? = null

        /**
         * Get the singleton instance of ServerConfigPreferences.
         */
        fun getInstance(context: Context): ServerConfigPreferences {
            return instance ?: synchronized(this) {
                instance ?: ServerConfigPreferences(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Save the server IP address.
     */
    fun saveIpAddress(ipAddress: String) {
        prefs.edit()
            .putString(KEY_IP_ADDRESS, ipAddress)
            .apply()
    }

    /**
     * Get the saved server IP address, or return the default if none is saved.
     */
    fun getIpAddress(): String {
        return prefs.getString(KEY_IP_ADDRESS, DEFAULT_IP_ADDRESS) ?: DEFAULT_IP_ADDRESS
    }

    /**
     * Save the server port.
     */
    fun savePort(port: String) {
        prefs.edit()
            .putString(KEY_PORT, port)
            .apply()
    }

    /**
     * Get the saved server port, or return the default if none is saved.
     */
    fun getPort(): String {
        return prefs.getString(KEY_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
    }

    /**
     * Save both IP address and port together.
     */
    fun saveServerConfig(ipAddress: String, port: String) {
        prefs.edit()
            .putString(KEY_IP_ADDRESS, ipAddress)
            .putString(KEY_PORT, port)
            .apply()
    }
}

