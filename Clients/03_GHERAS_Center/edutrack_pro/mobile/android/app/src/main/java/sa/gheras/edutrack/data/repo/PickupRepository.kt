package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sa.gheras.edutrack.data.dao.PickupDao
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.PickupDelegateEntity
import sa.gheras.edutrack.data.remote.PickupApi
import sa.gheras.edutrack.data.remote.dto.CancelPickupRequest
import sa.gheras.edutrack.data.remote.dto.CreatePickupRequest
import sa.gheras.edutrack.data.remote.dto.FailPickupRequest
import sa.gheras.edutrack.data.remote.dto.HandoverRequest
import sa.gheras.edutrack.data.remote.dto.PickupRequestDto
import sa.gheras.edutrack.sync.Outbox
import java.util.UUID

class PickupRepository(
    private val pickupDao: PickupDao,
    private val pickupApi: PickupApi,
    private val outbox: Outbox,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    fun observeDelegates(studentId: String): Flow<List<PickupDelegateEntity>> =
        pickupDao.observeDelegatesByStudent(studentId)

    fun observeDelegatesForStudents(studentIds: List<String>): Flow<List<PickupDelegateEntity>> =
        pickupDao.observeDelegatesByStudents(studentIds)

    suspend fun getDelegateById(id: String): PickupDelegateEntity? = withContext(ioDispatcher) {
        pickupDao.getDelegateById(id)
    }

    suspend fun createDelegate(
        studentId: String,
        name: String,
        nationalId: String,
        phone: String,
        relation: String
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val clientRequestId = UUID.randomUUID().toString()
            val payload = JSONObject().apply {
                put("id", "local:$clientRequestId")
                put("student_id", studentId)
                put("name", name)
                put("national_id", nationalId)
                put("phone", phone)
                put("relation", relation)
                put("client_request_id", clientRequestId)
            }.toString()

            val naturalKey = "delegate:$studentId:$nationalId"
            outbox.enqueue(
                kind = PendingWriteEntity.KIND_CREATE_PICKUP_DELEGATE,
                naturalKey = naturalKey,
                payloadJson = payload
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteDelegate(delegateId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val payload = JSONObject().apply {
                put("delegate_id", delegateId)
            }.toString()

            val naturalKey = "delete_delegate:$delegateId"
            outbox.enqueue(
                kind = PendingWriteEntity.KIND_DELETE_PICKUP_DELEGATE,
                naturalKey = naturalKey,
                payloadJson = payload
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestPickup(
        studentId: String,
        delegateId: String? = null,
        isEarlyLeave: Boolean = false
    ): Result<PickupRequestDto> = withContext(ioDispatcher) {
        try {
            val clientRequestId = UUID.randomUUID().toString()
            val body = CreatePickupRequest(
                studentId = studentId,
                delegateId = delegateId,
                isEarlyLeave = isEarlyLeave,
                clientRequestId = clientRequestId
            )
            val response = pickupApi.createRequest(body)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getActiveRequests(): Result<List<PickupRequestDto>> = withContext(ioDispatcher) {
        try {
            val response = pickupApi.getActiveRequests()
            Result.success(response.requests)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRequestById(id: String): Result<PickupRequestDto> = withContext(ioDispatcher) {
        try {
            val response = pickupApi.getRequestById(id)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun approveEarlyLeave(requestId: String): Result<PickupRequestDto> = withContext(ioDispatcher) {
        try {
            val response = pickupApi.approveEarlyLeave(requestId)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun startPrep(requestId: String): Result<PickupRequestDto> = withContext(ioDispatcher) {
        try {
            val response = pickupApi.startPrep(requestId)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markReady(requestId: String): Result<PickupRequestDto> = withContext(ioDispatcher) {
        try {
            val response = pickupApi.markReady(requestId)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun handover(
        requestId: String,
        verifiedReceiverIdNo: String,
        verifiedReceiverName: String,
        verifiedReceiverPhone: String,
        verifiedReceiverRelation: String
    ): Result<PickupRequestDto> = withContext(ioDispatcher) {
        try {
            val request = HandoverRequest(
                verifiedReceiverIdNo = verifiedReceiverIdNo,
                verifiedReceiverName = verifiedReceiverName,
                verifiedReceiverPhone = verifiedReceiverPhone,
                verifiedReceiverRelation = verifiedReceiverRelation
            )
            val response = pickupApi.handover(requestId, request)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cancelPickup(requestId: String, reason: String): Result<PickupRequestDto> = withContext(ioDispatcher) {
        try {
            val response = pickupApi.cancelRequest(requestId, CancelPickupRequest(reason))
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun failPickup(requestId: String, reason: String): Result<PickupRequestDto> = withContext(ioDispatcher) {
        try {
            val response = pickupApi.failRequest(requestId, FailPickupRequest(reason))
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
