/*
 * Copyright (c) 2021 dzikoysk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.reposilite.config

import net.dzikoysk.cdn.CdnFactory
import net.dzikoysk.dynamiclogger.Journalist
import net.dzikoysk.dynamiclogger.Logger
import panda.utilities.ClassUtils
import panda.utilities.StringUtils
import picocli.CommandLine
import java.nio.file.Path
import kotlin.reflect.full.isSubclassOf

class ConfigurationLoader(private val journalist: Journalist) : Journalist {

    companion object {

        fun <CONFIGURATION : Runnable> loadConfiguration(configuration: CONFIGURATION, description: String): Pair<String, CONFIGURATION> =
            description.split(" ", limit = 1)
                .let { Pair(it[0], it[1]) }
                .also { CommandLine.populateCommand(configuration, *it.second.split(" ").toTypedArray()) }
                .let { Pair(it.first, configuration) }
                .also { it.second.run() }

    }

    fun tryLoad(customConfigurationFile: Path): Configuration {
        return try {
            load(customConfigurationFile)
        } catch (exception: Exception) {
            throw RuntimeException("Cannot load configuration", exception)
        }
    }

    private fun load(configurationFile: Path): Configuration =
        CdnFactory.createStandard().let { cdn ->
            cdn.load(configurationFile.toFile(), Configuration::class.java).also {
                verifyBasePath(it)
                // verifyProxied(configuration)
                cdn.render(it, configurationFile.toFile())
                loadProperties(it)
            }
        }

    private fun verifyBasePath(configuration: Configuration) {
        var basePath = configuration.basePath

        if (!StringUtils.isEmpty(basePath)) {
            if (!basePath.startsWith("/")) {
                basePath = "/$basePath"
            }

            if (!basePath.endsWith("/")) {
                basePath += "/"
            }

            configuration.basePath = basePath
        }
    }

//    private fun verifyProxied(configuration: Configuration) {
//        for (index in configuration.proxied.indices) {
//            val proxied = configuration.proxied[index]
//
//            if (proxied.endsWith("/")) {
//                configuration.proxied[index] = proxied.substring(0, proxied.length - 1)
//            }
//        }
//    }

    private fun loadProperties(configuration: Configuration) {
        for (declaredField in configuration.javaClass.declaredFields) {
            val custom = System.getProperty("reposilite." + declaredField.name)

            if (StringUtils.isEmpty(custom)) {
                continue
            }

            val type = ClassUtils.getNonPrimitiveClass(declaredField.type).kotlin

            val customValue: Any? =
                if (String::class == type) {
                    custom
                }
                else if (Int::class == type) {
                    custom.toInt()
                }
                else if (Boolean::class == type) {
                    java.lang.Boolean.parseBoolean(custom)
                }
                else if (MutableCollection::class.isSubclassOf(type)) {
                    listOf(*custom.split(",").toTypedArray())
                }
                else {
                    logger.info("Unsupported type: $type for $custom")
                    continue
                }

            try {
                declaredField.isAccessible = true
                declaredField[configuration] = customValue
            } catch (illegalAccessException: IllegalAccessException) {
                throw RuntimeException("Cannot modify configuration value", illegalAccessException)
            }
        }
    }

    override fun getLogger(): Logger =
        journalist.logger

}