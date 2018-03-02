package android.fitget

import android.arch.persistence.room.*
import io.reactivex.Flowable

@Entity(tableName="plan")
class Plan (@ColumnInfo(name = "title") var title: String) {
    @PrimaryKey(autoGenerate = true)
    var uid:Int = 0

    @Dao
    interface PlanDao {
        @Insert
        fun insert(plan: Plan)

        @Delete
        fun delete(plan: Plan)

        @Query("SELECT * FROM plan")
        fun getAll(): Flowable<List<Plan>>
    }
}

