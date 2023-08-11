package net.onelitefeather.blackhole.spec.models

import net.onelitefeather.blackhole.util.PERMANENT_IDENTIFIER
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Duration
import java.time.Instant


/**
 * @author theEvilReaper
 * @version 1.0.0
 * @since
 **/

@Document("templates")
data class InjuryTemplate(
    @Id
    val id: String,
    val name: String,
    val messageKey: String,
    val descriptionKey: String,
    val identifier: String,
    val active: Boolean,
    val type: InjuryType,
    val duration: Duration,
    val points: Double,
    @CreatedDate
    val created: Instant,
    @CreatedBy
    val createdBy: String,
    @LastModifiedDate
    val modified: Instant,
    @LastModifiedBy
    val modifiedBy: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as InjuryTemplate

        if (name != other.name) return false
        if (messageKey != other.messageKey) return false
        if (descriptionKey != other.descriptionKey) return false
        if (identifier != other.identifier) return false
        if (active != other.active) return false
        if (type != other.type) return false
        if (duration != other.duration) return false
        if (points != other.points) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + messageKey.hashCode()
        result = 31 * result + descriptionKey.hashCode()
        result = 31 * result + identifier.hashCode()
        result = 31 * result + active.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + points.hashCode()
        return result
    }

    fun isPermanentTemplate(): Boolean = points == PERMANENT_IDENTIFIER
}