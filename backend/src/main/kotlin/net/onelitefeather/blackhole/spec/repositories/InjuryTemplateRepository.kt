package net.onelitefeather.blackhole.spec.repositories

import net.onelitefeather.blackhole.spec.models.InjuryTemplate
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * @author theEvilReaper
 * @version 1.0.0
 * @since
 **/
@Repository
interface InjuryTemplateRepository : MongoRepository<InjuryTemplate, String> {

    fun getTemplateByName(name: String): Optional<InjuryTemplate>

    fun deleteByName(name: String): InjuryTemplate
}