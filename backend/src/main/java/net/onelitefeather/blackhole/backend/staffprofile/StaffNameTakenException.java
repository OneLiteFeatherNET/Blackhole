package net.onelitefeather.blackhole.backend.staffprofile;

/** The database refused a profile whose folded name another staff member already holds (a race past the pre-check). */
public class StaffNameTakenException extends RuntimeException {

    public StaffNameTakenException() {
        super("the display name is already taken");
    }
}
