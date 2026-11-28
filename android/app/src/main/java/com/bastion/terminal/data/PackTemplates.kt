package com.bastion.terminal.data

/**
 * Plain-language starting points for a pack. Each gives a fill-in plan with
 * headings (the headings are what make the AI find the right part later:
 * "where is the rally point" lands on "Rally points"). Pure data.
 */
enum class PackKind(val label: String, val blurb: String, val planTitle: String, val template: String) {
    TRIP(
        "Trip or outing", "Where you're going, when you're back, who to call.", "Trip plan",
        """
        ## Where and when
        Destination:
        Leaving:
        Back by:

        ## Route
        Start point:
        Waypoints:
        Turn-around time:

        ## Who is going
        Name — callsign — phone:

        ## Check-ins
        Times:
        Channel:
        If a check-in is missed:

        ## Rally points
        Primary:
        Backup:

        ## Emergency
        Nearest hospital:
        Who to call if we're overdue:
        Vehicle parked at:
        """,
    ),
    HUNT(
        "Hunting plan", "Area, camp, tags and rules, check-ins, emergency plan.", "Hunting plan",
        """
        ## Area and dates
        Unit / area:
        Dates:
        Land access and permissions:

        ## Camp and rally points
        Camp location:
        Rally point if separated:
        Vehicle parked at:

        ## Who is hunting where
        Name — callsign — stand or area:

        ## Tags, seasons and rules
        Tags held:
        Legal shooting hours:
        Local rules to remember:

        ## Check-ins
        Times:
        Channel:
        If a check-in is missed:

        ## Game recovery
        Pack-out route:
        Processing / cooler location:

        ## Emergency
        Nearest hospital:
        Game warden / sheriff:
        Who to call if we're overdue:
        """,
    ),
    BUGOUT(
        "Bug-out plan", "Where to go, how to get there, what to take, how to regroup.", "Bug-out plan",
        """
        ## Trigger
        We leave when:

        ## Where we go
        Primary location:
        Backup location:

        ## Routes
        Primary route:
        Alternate route:
        Fuel stops:

        ## Rally points
        Near home:
        Out of town:
        How long we wait:

        ## Who and what
        People and pets:
        Grab list:
        Documents and cash:

        ## Communications
        Channel:
        Check-in times:
        If phones are down:

        ## Supplies at the destination
        Water:
        Food:
        Power:
        Medical:
        """,
    ),
    TEAM(
        "Team plan", "Roles, objective, movement, comms and what to do if it changes.", "Team plan",
        """
        ## Objective
        What we are doing:
        Where:
        When:

        ## Team and roles
        Name — callsign — role:

        ## Movement
        Start point:
        Route:
        Waypoints and timings:

        ## Communications
        Primary channel:
        Backup channel:
        Check-in schedule:
        Code words:

        ## Rally points
        Primary:
        Backup:

        ## If things change
        Lost contact:
        Injury:
        Weather or abort criteria:

        ## Medical
        Medic:
        Casualty collection point:
        Nearest hospital:
        """,
    ),
    CONTACTS(
        "Contact list", "People, addresses, phone numbers, callsigns.", "Contacts",
        """
        ## Name
        Role:
        Phone:
        Address:
        Radio / callsign:
        Notes:

        ## Name
        Role:
        Phone:
        Address:
        Radio / callsign:
        Notes:

        ## Name
        Role:
        Phone:
        Address:
        Radio / callsign:
        Notes:
        """,
    ),
    REFERENCE(
        "Reference", "Manuals, guides and notes you want kept together.", "Notes",
        """
        ## Topic

        """,
    );

    /** The fill-in text for a new plan of this kind. */
    fun starter(): String = template.trimIndent().trim() + "\n"

    companion object {
        fun of(name: String): PackKind = entries.firstOrNull { it.name == name } ?: REFERENCE
    }
}
