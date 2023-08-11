package net.onelitefeather.blackhole.spec.repositories

import net.onelitefeather.blackhole.spec.models.InjuryEntry
import org.springframework.data.mongodb.repository.MongoRepository

/**
 * @author theEvilReaper
 * @version 1.0.0
 * @since
 **/
interface InjuryEntryRepository : MongoRepository<InjuryEntry, String> {}