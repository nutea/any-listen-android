package io.github.nutea.anylisten.core.model

/** Constants taken from any-listen webserver-v0.11.0-beta.1 public source. */
object ProtocolConstants {
    const val TARGET_SERVER_VERSION = "0.11.0-beta.1"
    const val SOURCE_COMMIT = "e4ef53a5094473687e2d142fb7b63a530436b982"
    const val SOURCE_TAG = "webserver-v0.11.0-beta.1"

    const val API_PREFIX = "/api"
    const val HELLO_PATH = "/api/ipc/hello"
    const val ID_PATH = "/api/ipc/id"
    const val AUTH_PATH = "/api/ipc/ah"
    const val SOCKET_PATH = "/api/ipc/socket"
    const val PROXY_TOKEN_PATH = "/api/proxyUrlToken"
    const val P_STATIC_PREFIX = "/api/p_static/"
    const val P_URL_PREFIX = "/api/p_url/"
    const val PUBLIC_MEDIA_PREFIX = "/public/medias/"
    const val VIRTUAL_PROTOCOL = "al-ps-host:"

    const val HELLO_MSG = "Hello~::^-^::~v1~"
    const val ID_PREFIX = "OjppZDo6-"
    const val AUTH_FAILED = "Auth failed"
    const val BLOCKED_IP = "Blocked IP"

    const val WIN_TYPE_MAIN = "main"
    const val CLOSE_LOGOUT = 4001
    const val CLOSE_FAILED = 4100

    const val LIST_DEFAULT = "default"
    const val LIST_LOVE = "love"
    const val LIST_LAST_PLAYED = "last_played"

    const val ADD_LOCATION_BOTTOM = "bottom"
    const val ADD_LOCATION_TOP = "top"
    const val ACTION_MUSIC_ADD = "list_music_add"
    const val ACTION_MUSIC_REMOVE = "list_music_remove"
    const val ACTION_MUSIC_UPDATE_POSITION = "list_music_update_position"

    /** Web-server `updateLatestPlayList` keeps at most this many `last_played` entries. */
    const val LAST_PLAYED_LIMIT = 1000

    const val PROXY_COOKIE = "p_urlkey"
}
