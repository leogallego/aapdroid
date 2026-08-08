# Android Networking with Retrofit

Source: [new-silvermoon/awesome-android-agent-skills](https://github.com/new-silvermoon/awesome-android-agent-skills) (Apache 2.0)

## 1. URL Manipulation

Retrofit allows dynamic URL updates through replacement blocks and query parameters.

- **Dynamic Paths**: Use `{name}` in the relative URL and `@Path("name")` in parameters.
- **Query Parameters**: Use `@Query("key")` for individual parameters.
- **Complex Queries**: Use `@QueryMap Map<String, String>` for dynamic sets of parameters.

```kotlin
interface SearchService {
    @GET("group/{id}/users")
    suspend fun groupList(
        @Path("id") groupId: Int,
        @Query("sort") sort: String?,
        @QueryMap options: Map<String, String> = emptyMap()
    ): List<User>
}
```

## 2. Request Body & Form Data

- **@Body**: Serializes an object using the configured converter (JSON).
- **@FormUrlEncoded**: Sends data as `application/x-www-form-urlencoded`. Use `@Field`.
- **@Multipart**: Sends data as `multipart/form-data`. Use `@Part`.

```kotlin
interface UserService {
    @POST("users/new")
    suspend fun createUser(@Body user: User): User

    @FormUrlEncoded
    @POST("user/edit")
    suspend fun updateUser(
        @Field("first_name") first: String,
        @Field("last_name") last: String
    ): User

    @Multipart
    @PUT("user/photo")
    suspend fun uploadPhoto(
        @Part("description") description: RequestBody,
        @Part photo: MultipartBody.Part
    ): User
}
```

## 3. Header Manipulation

- **Static Headers**: Use `@Headers`.
- **Dynamic Headers**: Use `@Header`.
- **Header Maps**: Use `@HeaderMap`.
- **Global Headers**: Use an OkHttp **Interceptor** (e.g., auth token injection).

```kotlin
interface WidgetService {
    @Headers("Cache-Control: max-age=640000")
    @GET("widget/list")
    suspend fun widgetList(): List<Widget>

    @GET("user")
    suspend fun getUser(@Header("Authorization") token: String): User
}
```

## 4. Kotlin Support & Response Handling

When using `suspend` functions, two return type choices:

1. **Direct Body (`User`)**: Returns the deserialized body. Throws `HttpException` for non-2xx responses.
2. **`Response<User>`**: Provides access to the status code, headers, and error body. Does NOT throw on non-2xx results.

```kotlin
@GET("users")
suspend fun getUsers(): List<User> // Throws on error

@GET("users")
suspend fun getUsersResponse(): Response<List<User>> // Manual check
```

## 5. DI & Serialization Configuration

Provide Retrofit instances as singletons via Koin (this project uses Koin, not Hilt):

```kotlin
val networkModule = module {
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    single<OkHttpClient> {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(get())
            .addConverterFactory(get<Json>().asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
```

## 6. Error Handling in Repositories

Handle network exceptions in the Repository layer to keep the UI state clean.

```kotlin
class AapRepository(private val service: AapApiService) {
    suspend fun getTemplates(): Result<List<JobTemplate>> = runCatching {
        service.listJobTemplates()
    }.onFailure { exception ->
        // Handle UnknownHostException, SocketTimeoutException, HttpException, etc.
    }
}
```

## 7. Checklist

- [ ] Use `suspend` functions for all network calls.
- [ ] Prefer `Response<T>` if you need to handle specific status codes (e.g., 401 Unauthorized).
- [ ] Use `@Path` and `@Query` instead of manual string concatenation for URLs.
- [ ] Configure `OkHttpClient` with logging (for debug) and sensible timeouts.
- [ ] Map API DTOs to Domain models to decouple layers.
