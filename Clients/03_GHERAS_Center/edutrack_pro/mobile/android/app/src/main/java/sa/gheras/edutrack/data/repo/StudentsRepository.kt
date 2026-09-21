package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.dao.StudentDao
import sa.gheras.edutrack.data.entity.StudentEntity

class StudentsRepository(
    private val studentDao: StudentDao
) {
    fun observeAll(): Flow<List<StudentEntity>> = studentDao.observeAll()

    fun observeByRoom(roomId: String): Flow<List<StudentEntity>> = studentDao.observeByRoom(roomId)

    fun observeByIds(ids: List<String>): Flow<List<StudentEntity>> = studentDao.observeByIds(ids)

    suspend fun getById(id: String): StudentEntity? = studentDao.getById(id)

    fun searchByName(query: String): Flow<List<StudentEntity>> = studentDao.searchByName(query)
}
