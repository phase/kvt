package io.jadon.music.web.rest

import io.jadon.music.MusicServer
import io.jadon.music.model.*
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.mindrot.jbcrypt.BCrypt

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Path(val path: String, val method: HttpMethod = HttpMethod.GET)

open class RestApi(private val version: Int) {

    protected fun errorJson(error: String): JsonObject {
        val j = JsonObject()
        j.put("error", error)
        return j
    }

    lateinit var router: Router
    var initialized = false

    fun init(router: Router) {
        if (initialized) {
            throw RuntimeException("RestApi was already initialized!")
        }
        if (this.javaClass.name == RestApi::class.java.name) {
            throw RuntimeException("You need to implement RestApi with your JSON Paths!")
        }
        this.router = router

        initialized = true

        generatePaths()
    }

    private fun generatePaths() {
        // get paths through reflection
        this.javaClass.methods.filter { it.isAnnotationPresent(Path::class.java) }.forEach { method ->
            val annotation = method.getAnnotation(Path::class.java)
            val route = router.route(annotation.method, "/api/v$version" + annotation.path)

            route.handler { routingContext ->
                val parameters = mutableListOf<Any?>()
                val paramMap = when (annotation.method) {
                    HttpMethod.GET -> routingContext.request().params().map { it.key to it.value }.toMap()
                    HttpMethod.POST -> mapOf() // TODO
                    else -> null
                }
                paramMap?.entries?.forEachIndexed { i, routingParam ->
                    val methodParam = method.parameters.getOrNull(i)
                    if (methodParam == null) {
                        throw RuntimeException("Called ${method.name} with ${routingParam.key}=${routingParam.value}")
                    } else {
                        parameters.add(routingParam.value.toString())
                    }
                }
                val obj = try {
                    while (parameters.size < method.parameterCount) {
                        parameters.add(0, null)
                    }
                    val mappedParams = parameters.mapIndexed { index, o ->
                        val type = method.parameterTypes[index]
                        val ret: Any? = if (o == null) {
                            type.cast(null)
                        } else {
                            when (type) {
                                Int::class.java -> o.toString().toInt()
                                String::class.java -> o.toString()
                                else -> o
                            }
                        }
                        ret
                    }
                    method.invoke(this, *mappedParams.toTypedArray())
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorJson("invalid call to ${method.name}")
                }
                if (obj is JsonObject) {
                    routingContext.response().putHeader("content-type", "text/json")
                    // TODO: Prod - obj.encode()
                    routingContext.response().end(obj.encodePrettily())
                } else if (obj is Future<*>) {
                    (obj as Future<JsonObject>).setHandler {
                        routingContext.response().putHeader("content-type", "text/json")
                        if (it.succeeded()) {
                            val result = it.result()
                            println("'${method.name}' returned '${result.encode()}'")
                            routingContext.response().end(result.encodePrettily())
                        } else {
                            // TODO: Prod - obj.encode()
                            routingContext.response().end(
                                    errorJson("internal server error (future didn't succeed").encodePrettily()
                            )
                            it.cause().printStackTrace()
                        }
                    }
                } else {
                    throw RuntimeException("Method didn't return a JsonObject or Future! ($obj)")
                }
            }
        }
    }
}

object RestApiV1 : RestApi(1) {

    private fun encode(obj: Entity?, error: String = "null result"): JsonObject =
            if (obj == null) errorJson(error)
            else JsonObject(Json.encode(obj))
                    // get the type name from reflection
                    .put("type", obj.javaClass.simpleName.split(".").last().toLowerCase())

    // song paths

    @Path("/song/:id")
    fun song(id: Int): Future<JsonObject> {
        return MusicServer.database.getSong(id).compose {
            Future.succeededFuture(encode(it))
        }
    }

    // artist paths

    @Path("/artist/:id")
    fun artist(id: Int): Future<JsonObject> {
        return MusicServer.database.getArtist(id).compose {
            Future.succeededFuture(encode(it))
        }
    }

    // album paths

    @Path("/album/:id")
    fun album(id: Int): Future<JsonObject> {
        return MusicServer.database.getAlbum(id).compose {
            Future.succeededFuture(encode(it))
        }
    }

    @Path("/search/:q")
    fun search(q: String): Future<JsonObject> {
        return CompositeFuture.all(MusicServer.database.searchArtists(q), MusicServer.database.searchSongs(q), MusicServer.database.searchAlbums(q)).compose {
            val o = JsonObject()
            o.put("artistIds", JsonArray(it.resultAt<List<Artist>>(0).map { it.id }))
            o.put("songIds", JsonArray(it.resultAt<List<Song>>(1).map { it.id }))
            o.put("albumIds", JsonArray(it.resultAt<List<Album>>(2).map { it.id }))
            Future.succeededFuture(o)
        }
    }

    // account

    @Path("/login")
    fun login(username: String?, password: String?): Future<JsonObject> {
        println("LOGIN $username $password")
        return if (username.isNullOrBlank() || password.isNullOrBlank()) {
            Future.succeededFuture(errorJson("missing username or password"))
        } else
            MusicServer.database.getUserFromName(username!!).compose {
                if (it == null) return@compose Future.succeededFuture(errorJson("couldn't find user"))
                val correctPassword = BCrypt.checkpw(password!!, it.passwordHash)
                if (!correctPassword) return@compose Future.succeededFuture(errorJson("wrong password"))
                MusicServer.database.loginUser(it).compose {
                    val o = JsonObject()
                    o.put("token", it.toString())
                    Future.succeededFuture(o)
                }
            }
    }

    @Path("/validate")
    fun validate(token: String?): Future<JsonObject> {
        println("VALIDATE $token")
        return if (token == null) {
            Future.succeededFuture(JsonObject(mapOf("valid" to false)))
        } else MusicServer.database.isValidToken(token).compose {
            Future.succeededFuture(JsonObject(mapOf("valid" to it)))
        }
    }

    // user home page

    @Path("/stats")
    fun stats(token: String?): Future<JsonObject> {
        if (token == null) {
            return Future.succeededFuture(errorJson("invalid token"))
        }
        return MusicServer.database.getUser(token).compose {
            if (it == null) {
                Future.succeededFuture(errorJson("invalid token"))
            } else {
                CompositeFuture.all(MusicServer.database.getRecentEntityCount(it), MusicServer.database.getNewEntityCount()).compose {
                    Future.succeededFuture(JsonObject(mapOf(
                            "recentCount" to it.resultAt(0),
                            "newCount" to it.resultAt(1)
                    )))
                }
            }
        }
    }

    @Path("/new/:offset")
    fun new(token: String?, offset: Int): Future<JsonObject> {
        println("NEW $offset $token")
        if (token == null) {
            return Future.succeededFuture(errorJson("invalid token"))
        }
        return MusicServer.database.getUser(token).compose {
            if (it == null) {
                Future.succeededFuture(errorJson("invalid token"))
            } else {
                MusicServer.database.getNewEntity(offset).compose {
                    if (it == null) {
                        Future.succeededFuture(errorJson("no new entity found"))
                    } else {
                        Future.succeededFuture(encode(it))
                    }
                }
            }
        }
    }

    @Path("/recent/:offset")
    fun recent(token: String?, offset: Int): Future<JsonObject> {
        println("RECENT $offset $token")
        if (token == null) {
            return Future.succeededFuture(errorJson("invalid token"))
        }
        return MusicServer.database.getUser(token).compose {
            if (it == null) {
                Future.succeededFuture(errorJson("invalid token"))
            } else {
                MusicServer.database.getRecentEntity(it, offset).compose {
                    if (it == null) {
                        Future.succeededFuture(errorJson("no recent entity found"))
                    } else {
                        Future.succeededFuture(encode(it))
                    }
                }
            }
        }
    }

    @Path("/user/:id")
    fun user(id: Int): Future<JsonObject> {
        return MusicServer.database.getUser(id).compose {
            if (it == null) {
                Future.succeededFuture(errorJson("invalid token"))
            } else {
                Future.succeededFuture(encode(it))
            }
        }
    }

}
