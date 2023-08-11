package net.onelitefeather.blackhole.spec.repositories

import net.onelitefeather.blackhole.spec.models.InjuryProfile
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * @author theEvilReaper
 * @version 1.0.0
 * @since
 **/
interface InjuryProfileRepository : MongoRepository<InjuryProfile, String> {}