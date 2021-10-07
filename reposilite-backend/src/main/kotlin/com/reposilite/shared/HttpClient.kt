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

package com.reposilite.shared

import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.FuelError
import com.github.kittinunf.fuel.core.Headers.Companion.CONTENT_ENCODING
import com.github.kittinunf.fuel.core.Headers.Companion.CONTENT_TYPE
import com.github.kittinunf.fuel.core.Request
import com.github.kittinunf.fuel.core.Response
import com.github.kittinunf.fuel.core.extensions.authentication
import com.github.kittinunf.fuel.core.isSuccessful
import com.github.kittinunf.fuel.core.responseUnit
import com.reposilite.journalist.Channel
import com.reposilite.journalist.Journalist
import com.reposilite.maven.api.DocumentInfo
import com.reposilite.maven.api.FileDetails
import com.reposilite.maven.api.UNKNOWN_LENGTH
import com.reposilite.shared.FilesUtils.getExtension
import com.reposilite.web.http.ErrorResponse
import com.reposilite.web.http.errorResponse
import io.javalin.http.ContentType
import io.javalin.http.HttpCode.BAD_REQUEST
import io.javalin.http.HttpCode.NOT_ACCEPTABLE
import panda.std.Result
import panda.std.asSuccess
import java.io.InputStream

interface RemoteClient {

    /**
     * @param uri - full remote host address with a gav
     * @param credentials - basic credentials in user:password format
     * @param connectTimeout - connection establishment timeout in seconds
     * @param readTimeout - connection read timeout in seconds
     */
    fun head(uri: String, credentials: String?, connectTimeout: Int, readTimeout: Int): Result<FileDetails, ErrorResponse>

    /**
     * @param uri - full remote host address with a gav
     * @param credentials - basic credentials in user:password format
     * @param connectTimeout - connection establishment timeout in seconds
     * @param readTimeout - connection read timeout in seconds
     */
    fun get(uri: String, credentials: String?, connectTimeout: Int, readTimeout: Int): Result<InputStream, ErrorResponse>

}

class HttpRemoteClient(private val journalist: Journalist) : RemoteClient {

    override fun head(uri: String, credentials: String?, connectTimeout: Int, readTimeout: Int): Result<FileDetails, ErrorResponse> =
        Fuel.head(uri)
            .authenticateWith(credentials)
            .timeout(connectTimeout * 1000)
            .timeoutRead(readTimeout * 1000)
            .responseUnit()
            .let { (_, response, result) ->
                result.fold(
                    success = { createHeadResponse(uri, response) },
                    failure = { error -> createErrorResponse(uri, error) }
                )
            }

    private fun createHeadResponse(uri: String, response: Response): Result<FileDetails, ErrorResponse> {
        val contentType = response.findHeader(CONTENT_TYPE)
            ?.let { ContentType.getContentType(it) }
            ?: ContentType.getContentTypeByExtension(uri.getExtension())
            ?: ContentType.APPLICATION_OCTET_STREAM

        // Nexus can send misleading for client content-length of chunked responses
        // ~ https://github.com/dzikoysk/reposilite/issues/549
        val contentLength =
            if ("gzip" == response.findHeader(CONTENT_ENCODING))
                UNKNOWN_LENGTH // remove content-length header
            else
                response.contentLength

        return when {
            contentType == ContentType.TEXT_HTML -> errorResponse(NOT_ACCEPTABLE, "Illegal file type")
            response.isSuccessful.not() -> errorResponse(NOT_ACCEPTABLE, "Unsuccessful request")
            else ->
                DocumentInfo(
                    response.url.path.toPath().getSimpleName(),
                    contentType,
                    contentLength
                ).asSuccess()
        }
    }

    override fun get(uri: String, credentials: String?, connectTimeout: Int, readTimeout: Int): Result<InputStream, ErrorResponse> =
        Fuel.get(uri)
            .authenticateWith(credentials)
            .responseUnit()
            .let { (_, response, result) ->
                result.fold(
                    success = { response.body().toStream().asSuccess() },
                    failure = { error -> createErrorResponse(uri, error) }
                )
            }

    private fun <V> createErrorResponse(uri: String, error: FuelError): Result<V, ErrorResponse> {
        journalist.logger.debug("Cannot get $uri")
        journalist.logger.exception(Channel.DEBUG, error.exception)
        return errorResponse(BAD_REQUEST, "An error of type ${error.exception.javaClass} happened: ${error.message}")
    }

    private fun Request.authenticateWith(credentials: String?): Request = also {
        if (credentials != null) {
            val (username, password) = credentials.split(":", limit = 2)
            it.authentication().basic(username, password)
        }
    }

    private fun Response.findHeader(value: String): String? =
        headers[value].lastOrNull()

}

class FakeRemoteClient(
    private val headHandler: (String, String?, Int, Int) -> Result<FileDetails, ErrorResponse>,
    private val getHandler: (String, String?, Int, Int) -> Result<InputStream, ErrorResponse>
) : RemoteClient {

    override fun head(uri: String, credentials: String?, connectTimeout: Int, readTimeout: Int): Result<FileDetails, ErrorResponse> =
        headHandler(uri, credentials, connectTimeout, readTimeout)

    override fun get(uri: String, credentials: String?, connectTimeout: Int, readTimeout: Int): Result<InputStream, ErrorResponse> =
        getHandler(uri, credentials, connectTimeout, readTimeout)

}