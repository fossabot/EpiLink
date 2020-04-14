package org.epilink.bot

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.*
import io.ktor.sessions.get
import io.ktor.sessions.sessions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.epilink.bot.db.Allowed
import org.epilink.bot.db.Disallowed
import org.epilink.bot.db.LinkServerDatabase
import org.epilink.bot.discord.LinkRoleManager
import org.epilink.bot.http.*
import org.epilink.bot.http.data.IdAccess
import org.epilink.bot.http.data.IdAccessLogs
import org.epilink.bot.http.sessions.ConnectedSession
import org.epilink.bot.http.sessions.RegisterSession
import org.koin.dsl.module
import org.koin.test.get
import java.time.Duration
import java.time.Instant
import kotlin.test.*

data class ApiSuccess(
    val success: Boolean,
    val message: String?,
    val data: Map<String, Any?>
) {
    init {
        assertTrue(success)
    }
}

data class ApiError(
    val success: Boolean,
    val message: String,
    val data: ApiErrorDetails
) {
    init {
        assertFalse(success)
    }
}

data class ApiErrorDetails(
    val code: Int,
    val description: String
)

class BackEndTest : KoinBaseTest(
    module {
        single<LinkBackEnd> { LinkBackEndImpl() }
        single<SessionStorageProvider> { MemoryStorageProvider() }
    }
) {
    @Test
    fun `Test meta information gathering`() {
        mockHere<LinkServerEnvironment> {
            every { name } returns "EpiLink Test Instance"
        }
        mockHere<LinkDiscordBackEnd> {
            every { getAuthorizeStub() } returns "I am a Discord authorize stub"
        }
        mockHere<LinkMicrosoftBackEnd> {
            every { getAuthorizeStub() } returns "I am a Microsoft authorize stub"
        }
        mockHere<LinkLegalTexts> {
            every { idPrompt } returns "My id prompt text is the best"
        }
        withTestEpiLink {
            val call = handleRequest(HttpMethod.Get, "/api/v1/meta/info")
            call.assertStatus(HttpStatusCode.OK)
            val data = fromJson<ApiSuccess>(call.response).data
            assertEquals("EpiLink Test Instance", data["title"])
            assertEquals(null, data.getValue("logo"))
            assertEquals("I am a Discord authorize stub", data.getString("authorizeStub_discord"))
            assertEquals("I am a Microsoft authorize stub", data.getString("authorizeStub_msft"))
            assertEquals("My id prompt text is the best", data.getString("idPrompt"))
        }
    }

    @Test
    fun `Test ToS retrieval`() {
        val tos = "<p>ABCDEFG</p>"
        mockHere<LinkLegalTexts> {
            every { tosText } returns tos
        }
        withTestEpiLink {
            val call = handleRequest(HttpMethod.Get, "/api/v1/meta/tos")
            call.assertStatus(HttpStatusCode.OK)
            assertEquals(ContentType.Text.Html, call.response.contentType())
            val data = call.response.content
            assertEquals(tos, data)
        }
    }

    @Test
    fun `Test PP retrieval`() {
        val pp = "<p>Privacy policyyyyyyyyyyyyyyyyyyyyyyyyyyyyy</p>"
        mockHere<LinkLegalTexts> {
            every { policyText } returns pp
        }
        withTestEpiLink {
            val call = handleRequest(HttpMethod.Get, "/api/v1/meta/privacy")
            call.assertStatus(HttpStatusCode.OK)
            assertEquals(ContentType.Text.Html, call.response.contentType())
            val data = call.response.content
            assertEquals(pp, data)
        }
    }

    @Test
    fun `Test Microsoft account authcode registration`() {
        mockHere<LinkMicrosoftBackEnd> {
            coEvery { getMicrosoftToken("fake mac", "fake mur") } returns "fake mtk"
            coEvery { getMicrosoftInfo("fake mtk") } returns MicrosoftUserInfo("fakeguid", "fakemail")
        }
        mockHere<LinkServerDatabase> {
            coEvery { isMicrosoftUserAllowedToCreateAccount(any()) } returns Allowed
        }
        withTestEpiLink {
            val call = handleRequest(HttpMethod.Post, "/api/v1/register/authcode/msft") {
                setJsonBody("""{"code":"fake mac","redirectUri":"fake mur"}""")
            }
            call.assertStatus(HttpStatusCode.OK)
            val data = fromJson<ApiSuccess>(call.response).data
            assertEquals("continue", data.getString("next"))
            val regInfo = data.getMap("attachment")
            assertEquals("fakemail", regInfo.getString("email"))
            assertEquals(null, regInfo.getValue("discordUsername"))
            assertEquals(null, regInfo.getValue("discordAvatarUrl"))
            val session = call.sessions.get<RegisterSession>()
            assertEquals(RegisterSession(microsoftUid = "fakeguid", email = "fakemail"), session)
        }
    }

    @Test
    fun `Test Microsoft account authcode registration when disallowed`() {
        mockHere<LinkMicrosoftBackEnd> {
            coEvery { getMicrosoftToken("fake mac", "fake mur") } returns "fake mtk"
            coEvery { getMicrosoftInfo("fake mtk") } returns MicrosoftUserInfo("fakeguid", "fakemail")
        }
        mockHere<LinkServerDatabase> {
            coEvery { isMicrosoftUserAllowedToCreateAccount(any()) } returns Disallowed("Cheh dans ta tronche")
        }
        withTestEpiLink {
            val call = handleRequest(HttpMethod.Post, "/api/v1/register/authcode/msft") {
                setJsonBody("""{"code":"fake mac","redirectUri":"fake mur"}""")
            }
            call.assertStatus(HttpStatusCode.BadRequest)
            val error = fromJson<ApiError>(call.response)
            assertEquals("Cheh dans ta tronche", error.message)
            assertEquals(101, error.data.code)
        }
    }

    @Test
    fun `Test Discord account authcode registration account does not exist`() {
        mockHere<LinkDiscordBackEnd> {
            coEvery { getDiscordToken("fake auth", "fake uri") } returns "fake yeet"
            coEvery { getDiscordInfo("fake yeet") } returns DiscordUserInfo("yes", "no", "maybe")
        }
        mockHere<LinkServerDatabase> {
            coEvery { isDiscordUserAllowedToCreateAccount(any()) } returns Allowed
            coEvery { getUser("yes") } returns null
        }
        withTestEpiLink {
            val call = handleRequest(HttpMethod.Post, "/api/v1/register/authcode/discord") {
                setJsonBody("""{"code":"fake auth","redirectUri":"fake uri"}""")
            }
            call.assertStatus(HttpStatusCode.OK)
            val data = fromJson<ApiSuccess>(call.response).data
            assertEquals("continue", data.getString("next"))
            val regInfo = data.getMap("attachment")
            assertEquals(null, regInfo.getValue("email"))
            assertEquals("no", regInfo.getString("discordUsername"))
            assertEquals("maybe", regInfo.getString("discordAvatarUrl"))
            val session = call.sessions.get<RegisterSession>()
            assertEquals(
                RegisterSession(discordId = "yes", discordUsername = "no", discordAvatarUrl = "maybe"),
                session
            )
        }
    }

    @Test
    fun `Test Discord account authcode registration account already exists`() {
        mockHere<LinkDiscordBackEnd> {
            coEvery { getDiscordToken("fake auth", "fake uri") } returns "fake yeet"
            coEvery { getDiscordInfo("fake yeet") } returns DiscordUserInfo("yes", "no", "maybe")
        }
        mockHere<LinkServerDatabase> {
            coEvery { getUser("yes") } returns mockk { every { discordId } returns "yes" }
        }
        withTestEpiLink {
            val call = handleRequest(HttpMethod.Post, "/api/v1/register/authcode/discord") {
                setJsonBody("""{"code":"fake auth","redirectUri":"fake uri"}""")
            }
            call.assertStatus(HttpStatusCode.OK)
            val data = fromJson<ApiSuccess>(call.response).data
            assertEquals("login", data.getString("next"))
            assertEquals(null, data.getValue("attachment"))
            val session = call.sessions.get<ConnectedSession>()
            assertEquals(
                ConnectedSession(discordId = "yes", discordUsername = "no", discordAvatar = "maybe"),
                session
            )
        }
    }

    @Test
    fun `Test Discord account authcode registration when disallowed`() {
        mockHere<LinkDiscordBackEnd> {
            coEvery { getDiscordToken("fake auth", "fake uri") } returns "fake yeet"
            coEvery { getDiscordInfo("fake yeet") } returns DiscordUserInfo("yes", "no", "maybe")
        }
        mockHere<LinkServerDatabase> {
            coEvery { getUser("yes") } returns null
            coEvery { isDiscordUserAllowedToCreateAccount(any()) } returns Disallowed("Cheh dans ta tête")
        }
        withTestEpiLink {
            val call = handleRequest(HttpMethod.Post, "/api/v1/register/authcode/discord") {
                setJsonBody("""{"code":"fake auth","redirectUri":"fake uri"}""")
            }
            call.assertStatus(HttpStatusCode.BadRequest)
            val error = fromJson<ApiError>(call.response)
            assertEquals("Cheh dans ta tête", error.message)
            assertEquals(101, error.data.code)
        }
    }

    @Test
    fun `Test registration session deletion`() {
        withTestEpiLink {
            val header = handleRequest(HttpMethod.Get, "/api/v1/register/info").run {
                assertStatus(HttpStatusCode.OK)
                assertNotNull(sessions.get<RegisterSession>())
                response.headers["RegistrationSessionId"]!!
            }
            handleRequest(HttpMethod.Delete, "/api/v1/register") {
                addHeader("RegistrationSessionId", header)
            }.apply {
                assertNull(sessions.get<RegisterSession>())
            }
        }
    }

    @Test
    fun `Test full registration sequence, discord then msft`() {
        mockHere<LinkDiscordBackEnd> {
            coEvery { getDiscordToken("fake auth", "fake uri") } returns "fake yeet"
            coEvery { getDiscordInfo("fake yeet") } returns DiscordUserInfo("yes", "no", "maybe")
        }
        mockHere<LinkMicrosoftBackEnd> {
            coEvery { getMicrosoftToken("fake mac", "fake mur") } returns "fake mtk"
            coEvery { getMicrosoftInfo("fake mtk") } returns MicrosoftUserInfo("fakeguid", "fakemail")
        }
        val db = mockHere<LinkServerDatabase> {
            coEvery { getUser("yes") } returns null
            coEvery { isDiscordUserAllowedToCreateAccount(any()) } returns Allowed
            coEvery { isMicrosoftUserAllowedToCreateAccount(any()) } returns Allowed
            coEvery { createUser(any(), any(), any(), any()) } returns mockk { every { discordId } returns "yes" }
        }
        val bot = mockHere<LinkRoleManager> {
            coEvery { updateRolesOnAllGuildsLater(any()) } returns mockk()
        }
        withTestEpiLink {
            val regHeader = handleRequest(HttpMethod.Post, "/api/v1/register/authcode/discord") {
                setJsonBody("""{"code":"fake auth","redirectUri":"fake uri"}""")
            }.run {
                assertStatus(HttpStatusCode.OK)
                val data = fromJson<ApiSuccess>(response).data
                assertEquals("continue", data.getString("next"))
                assertEquals("no", data.getMap("attachment").getString("discordUsername"))
                response.headers["RegistrationSessionId"]!!
            }
            handleRequest(HttpMethod.Post, "/api/v1/register/authcode/msft") {
                addHeader("RegistrationSessionId", regHeader)
                setJsonBody("""{"code":"fake mac","redirectUri":"fake mur"}""")
            }.apply {
                assertStatus(HttpStatusCode.OK)
                val data = fromJson<ApiSuccess>(response).data
                assertEquals("continue", data.getString("next"))
                assertEquals("fakemail", data.getMap("attachment").getString("email"))
                assertEquals("no", data.getMap("attachment").getString("discordUsername"))
            }
            val loginHeader = handleRequest(HttpMethod.Post, "/api/v1/register") {
                addHeader("RegistrationSessionId", regHeader)
                setJsonBody("""{"keepIdentity": true}""")
            }.run {
                assertStatus(HttpStatusCode.Created)
                assertNull(sessions.get<RegisterSession>())
                assertEquals(ConnectedSession("yes", "no", "maybe"), sessions.get<ConnectedSession>())
                response.headers["SessionId"]!!
            }
            coVerify { db.createUser("yes", "fakeguid", "fakemail", true) }
            // Simulate the DB knowing about the new user
            mockHere<LinkServerDatabase> {
                coEvery { getUser("yes") } returns mockk { every { discordId } returns "yes" }
            }
            handleRequest(HttpMethod.Get, "/api/v1/user") {
                addHeader("SessionId", loginHeader)
            }.apply {
                assertStatus(HttpStatusCode.OK)
                // Only checks that it was logged in properly. The results of /api/v1/user are tested elsewhere
                assertTrue { this.response.content!!.contains("yes") }
            }
            coVerify { bot.updateRolesOnAllGuildsLater(any()) }
        }
    }

    @Test
    fun `Test user without session id fails`() {
        withTestEpiLink {
            handleRequest(HttpMethod.Get, "/api/v1/user").run {
                assertStatus(HttpStatusCode.Unauthorized)
            }
        }
    }

    @Test
    fun `Test user with incorrect session id fails`() {
        withTestEpiLink {
            handleRequest(HttpMethod.Get, "/api/v1/user") {
                addHeader("SessionId", "eeebaaa")
            }.run {
                assertStatus(HttpStatusCode.Unauthorized)
            }
        }
    }

    @Test
    fun `Test user endpoint`() {
        withTestEpiLink {
            val sid = setupSession(
                discId = "myDiscordId",
                discUsername = "Discordian#1234",
                discAvatarUrl = "https://veryavatar"
            )
            handleRequest(HttpMethod.Get, "/api/v1/user") {
                addHeader("SessionId", sid)
            }.apply {
                assertStatus(HttpStatusCode.OK)
                val data = fromJson<ApiSuccess>(response)
                assertEquals("myDiscordId", data.data["discordId"])
                assertEquals("Discordian#1234", data.data["username"])
                assertEquals("https://veryavatar", data.data["avatarUrl"])
            }
        }
    }

    @Test
    fun `Test user access logs retrieval`() {
        val inst1 = Instant.now() - Duration.ofHours(1)
        val inst2 = Instant.now() - Duration.ofHours(10)
        mockHere<LinkServerDatabase> {
            coEvery { getIdAccessLogs("discordid") } returns IdAccessLogs(
                manualAuthorsDisclosed = false,
                accesses = listOf(
                    IdAccess(true, "Robot Robot Robot", "Yes", inst1.toString()),
                    IdAccess(false, null, "No", inst2.toString())
                )
            )
        }
        withTestEpiLink {
            val sid = setupSession(
                discId = "discordid"
            )
            handleRequest(HttpMethod.Get, "/api/v1/user/idaccesslogs") {
                addHeader("SessionId", sid)
            }.apply {
                assertStatus(HttpStatusCode.OK)
                fromJson<ApiSuccess>(response).apply {
                    assertEquals(false, data["manualAuthorsDisclosed"])
                    val list = data["accesses"] as? List<*> ?: error("Unexpected format on accesses")
                    assertEquals(2, list.size)
                    val first = list[0] as? Map<*, *> ?: error("Unexpected format")
                    assertEquals(true, first["automated"])
                    assertEquals("Robot Robot Robot", first["author"])
                    assertEquals("Yes", first["reason"])
                    assertEquals(inst1.toString(), first["timestamp"])
                    val second = list[1] as? Map<*, *> ?: error("Unexpected format")
                    assertEquals(false, second["automated"])
                    assertEquals(null, second.getOrDefault("author", "WRONG"))
                    assertEquals("No", second["reason"])
                    assertEquals(inst2.toString(), second["timestamp"])
                }
            }
        }
    }

    private fun TestApplicationEngine.setupSession(
        discId: String = "discordid",
        discUsername: String = "discorduser#1234",
        discAvatarUrl: String? = "https://avatar/url",
        msIdHash: ByteArray = byteArrayOf(1, 2, 3, 4, 5),
        created: Instant = Instant.now() - Duration.ofDays(1)
    ): String {
        softMockHere<LinkServerDatabase> {
            coEvery { getUser(discId) } returns mockk {
                every { discordId } returns discId
                every { msftIdHash } returns msIdHash
                every { creationDate } returns created
            }
        }
        softMockHere<LinkDiscordBackEnd> {
            coEvery { getDiscordToken("auth", "redir") } returns "tok"
            coEvery { getDiscordInfo("tok") } returns DiscordUserInfo(discId, discUsername, discAvatarUrl)
        }
        val call = handleRequest(HttpMethod.Post, "/api/v1/register/authcode/discord") {
            setJsonBody("""{"code":"auth","redirectUri":"redir"}""")
        }
        return call.response.headers["SessionId"] ?: error("Did not return a SessionId")
    }

    /**
     * Similar to mockHere, but if an instance of T is already injected, apply the initializer to it instead of
     * replacing it
     */
    private inline fun <reified T : Any> softMockHere(crossinline initializer: T.() -> Unit): T {
        val injected = getKoin().getOrNull<T>()
        return injected?.apply(initializer) ?: mockHere(initializer)
    }

    private fun TestApplicationRequest.setJsonBody(json: String) {
        addHeader("Content-Type", "application/json")
        setBody(json)
    }

    private fun withTestEpiLink(block: TestApplicationEngine.() -> Unit) =
        withTestApplication({
            with(get<LinkBackEnd>()) {
                epilinkApiModule()
            }
        }, block)
}