package net.onelitefeather.blackhole.handler

import net.onelitefeather.blackhole.spec.models.InjuryProfile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.*

@RestController
class InjuryProfileHandler {



    @RequestMapping("/profile/{id}", method = [RequestMethod.GET], produces = ["application/json"])
    fun getProfile(@PathVariable("id") id: String): ResponseEntity<InjuryProfile?> {
        val playerUUID = UUID.fromString(id)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The given uuid string can't be parsed to a uuid")

        return ResponseEntity.ok(null)
    }
}