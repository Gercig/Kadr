package ru.kadr.app.data

import android.content.Context
import ru.kadr.app.BuildConfig
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Адрес сервера задаётся в android/gradle.properties (строка kadr.serverUrl):
 *  - http://localhost:8000/ — локальный сервер через adb reverse;
 *  - HTTPS-адрес — развёрнутый сервер.
 */
val BASE_URL: String = BuildConfig.BASE_URL

interface KadrApi {
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterBody): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginBody): AuthResponse

    @POST("api/auth/logout")
    suspend fun logout(): Map<String, Any>

    @GET("api/me")
    suspend fun me(): User

    @GET("api/me/bonus")
    suspend fun bonus(): BonusInfo

    @GET("api/cinemas")
    suspend fun cinemas(): List<Cinema>

    @GET("api/cinemas/{id}/schedule")
    suspend fun schedule(@Path("id") cinemaId: Int, @Query("day") day: String): Schedule

    @GET("api/sessions/{id}")
    suspend fun session(@Path("id") sessionId: Int): SessionInfo

    @POST("api/orders")
    suspend fun createOrder(@Body body: OrderBody): Order

    @GET("api/orders/{id}")
    suspend fun order(@Path("id") orderId: Int): Order

    @POST("api/orders/{id}/pay")
    suspend fun pay(@Path("id") orderId: Int, @Body body: PayBody): Order

    @POST("api/orders/{id}/cancel")
    suspend fun cancel(@Path("id") orderId: Int): Order

    @GET("api/tickets")
    suspend fun tickets(): List<Order>

    // ---------- рабочий кабинет сотрудников
    @GET("api/staff/cinemas")
    suspend fun staffCinemas(): List<StaffCinema>

    @POST("api/staff/cinemas")
    suspend fun addCinema(@Body body: CinemaBody): StaffCinema

    @PUT("api/staff/cinemas/{id}")
    suspend fun editCinema(@Path("id") id: Int, @Body body: CinemaBody): StaffCinema

    @POST("api/staff/cinemas/{id}/halls")
    suspend fun addHall(@Path("id") cinemaId: Int, @Body body: HallBody): StaffHall

    @PUT("api/staff/halls/{id}")
    suspend fun editHall(@Path("id") id: Int, @Body body: HallBody): StaffHall

    @GET("api/staff/users")
    suspend fun staffUsers(@Query("q") q: String, @Query("role") role: String = ""): List<StaffUser>

    @POST("api/staff/users")
    suspend fun addStaff(@Body body: StaffBody): StaffUser

    @PATCH("api/staff/users/{id}")
    suspend fun editUser(@Path("id") id: Int, @Body body: UserPatch): StaffUser

    @GET("api/staff/films")
    suspend fun staffFilms(): List<StaffFilm>

    @POST("api/staff/films")
    suspend fun addFilm(@Body body: FilmBody): StaffFilm

    @PUT("api/staff/films/{id}")
    suspend fun editFilm(@Path("id") id: Int, @Body body: FilmBody): StaffFilm

    @GET("api/staff/sessions")
    suspend fun staffSessions(@Query("day") day: String, @Query("cinema_id") cinemaId: Int = 0): List<StaffSession>

    @POST("api/staff/sessions")
    suspend fun addSession(@Body body: SessionBody): Map<String, Any>

    @PUT("api/staff/sessions/{id}")
    suspend fun editSession(@Path("id") id: Int, @Body body: SessionBody): Map<String, Any>

    @DELETE("api/staff/sessions/{id}")
    suspend fun deleteSession(@Path("id") id: Int): Map<String, Any>

    @GET("api/staff/report")
    suspend fun report(@Query("date_from") from: String, @Query("date_to") to: String, @Query("cinema_id") cinemaId: Int = 0): SalesReport

    @GET("api/staff/payments")
    suspend fun payments(
        @Query("cinema_id") cinemaId: Int = 0,
        @Query("date_from") from: String = "",
        @Query("date_to") to: String = "",
        @Query("status") status: String = "",
        @Query("q") q: String = "",
    ): List<Payment>

    @POST("api/staff/orders/{id}/refund")
    suspend fun refund(@Path("id") orderId: Int, @Body body: RefundBody): Map<String, Any>

    @POST("api/staff/check")
    suspend fun checkTicket(@Body body: CheckBody): CheckResult
}

/** Хранит токен входа и выбранный кинотеатр между запусками приложения. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kadr", Context.MODE_PRIVATE)
    var token: String?
        get() = sp.getString("token", null)
        set(value) = sp.edit().putString("token", value).apply()
    var cinemaId: Int
        get() = sp.getInt("cinema", 1)
        set(value) = sp.edit().putInt("cinema", value).apply()
}

object ApiClient {
    lateinit var prefs: Prefs
        private set

    fun init(context: Context) {
        prefs = Prefs(context.applicationContext)
    }

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    val api: KadrApi by lazy {
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = prefs.token
                val request = if (token != null) {
                    chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .build()
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(KadrApi::class.java)
    }
}

/** Превращает ошибку запроса в понятный пользователю текст. */
fun Throwable.userMessage(): String = when (this) {
    is HttpException -> {
        val body = response()?.errorBody()?.string()
        val detail = try {
            val el = JsonParser.parseString(body ?: "").asJsonObject.get("detail")
            if (el != null && el.isJsonPrimitive) el.asString else null
        } catch (e: Exception) {
            null
        }
        detail ?: "Ошибка сервера (${code()})"
    }
    is IOException -> "Нет связи с сервером. Проверьте, что сервер запущен, и адрес в ApiClient.kt"
    else -> message ?: "Что-то пошло не так"
}
