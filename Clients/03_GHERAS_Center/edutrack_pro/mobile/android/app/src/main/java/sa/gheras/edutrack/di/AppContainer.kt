package sa.gheras.edutrack.di

import android.content.Context
import androidx.room.Room
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import sa.gheras.edutrack.BuildConfig
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.local.session.EncryptedPrefsSessionStore
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.remote.AuthApi
import sa.gheras.edutrack.data.remote.AuthInterceptor
import sa.gheras.edutrack.data.remote.MeApi
import sa.gheras.edutrack.data.repo.OutboxRepository
import sa.gheras.edutrack.data.repo.SessionRepository
import sa.gheras.edutrack.sync.Outbox
import sa.gheras.edutrack.sync.PullSync
import java.util.concurrent.TimeUnit

class AppContainer(private val context: Context) {

    val database: GherasDatabase by lazy {
        Room.databaseBuilder(
            context,
            GherasDatabase::class.java,
            GherasDatabase.DATABASE_NAME
        ).fallbackToDestructiveMigration().build()
    }

    val sessionStore: SessionStore by lazy {
        EncryptedPrefsSessionStore(context)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(sessionStore) {
                sessionRepository.onUnauthorized()
            })
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        val contentType = "application/json".toMediaType()
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    val authApi: AuthApi by lazy {
        retrofit.create(AuthApi::class.java)
    }

    val meApi: MeApi by lazy {
        retrofit.create(MeApi::class.java)
    }

    val outbox: Outbox by lazy {
        Outbox(context, database) { sessionStore.user?.id ?: "me" }
    }

    val outboxRepository: OutboxRepository by lazy {
        OutboxRepository(database.pendingWriteDao())
    }

    val pullSync: PullSync by lazy {
        PullSync(meApi, database, sessionStore, outbox)
    }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(
            store = sessionStore,
            authApi = authApi,
            meApi = meApi,
            db = database,
            onFullPullRequested = {
                pullSync.requestFull()
            }
        )
    }

    val studentsRepository: sa.gheras.edutrack.data.repo.StudentsRepository by lazy {
        sa.gheras.edutrack.data.repo.StudentsRepository(database.studentDao())
    }

    val scheduleRepository: sa.gheras.edutrack.data.repo.ScheduleRepository by lazy {
        sa.gheras.edutrack.data.repo.ScheduleRepository(database.scheduleDao())
    }

    val attendanceRepository: sa.gheras.edutrack.data.repo.AttendanceRepository by lazy {
        sa.gheras.edutrack.data.repo.AttendanceRepository(database.studentAttendanceDao(), outbox)
    }

    val evaluationsRepository: sa.gheras.edutrack.data.repo.EvaluationsRepository by lazy {
        sa.gheras.edutrack.data.repo.EvaluationsRepository(database.evaluationDao(), outbox)
    }

    val assignmentsRepository: sa.gheras.edutrack.data.repo.AssignmentsRepository by lazy {
        sa.gheras.edutrack.data.repo.AssignmentsRepository(database.assignmentDao(), database.submissionDao())
    }

    val lessonLogsRepository: sa.gheras.edutrack.data.repo.LessonLogsRepository by lazy {
        sa.gheras.edutrack.data.repo.LessonLogsRepository(database.lessonLogDao(), outbox)
    }

    val feesRepository: sa.gheras.edutrack.data.repo.FeesRepository by lazy {
        sa.gheras.edutrack.data.repo.FeesRepository(database.installmentDao(), database.receiptDao())
    }

    val notificationsRepository: sa.gheras.edutrack.data.repo.NotificationsRepository by lazy {
        sa.gheras.edutrack.data.repo.NotificationsRepository(database.notificationDao(), outbox)
    }
}
