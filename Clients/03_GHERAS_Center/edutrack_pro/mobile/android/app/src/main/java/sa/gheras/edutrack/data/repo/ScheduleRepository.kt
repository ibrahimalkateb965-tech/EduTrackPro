package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.dao.ScheduleDao
import sa.gheras.edutrack.data.entity.ScheduleEntity

class ScheduleRepository(
    private val scheduleDao: ScheduleDao
) {
    fun observeAll(): Flow<List<ScheduleEntity>> = scheduleDao.observeAll()

    fun observeByRoom(roomId: String): Flow<List<ScheduleEntity>> = scheduleDao.observeByRoom(roomId)

    fun observeByDay(day: String): Flow<List<ScheduleEntity>> = scheduleDao.observeByDay(day)

    suspend fun getById(id: String): ScheduleEntity? = scheduleDao.getById(id)
}
