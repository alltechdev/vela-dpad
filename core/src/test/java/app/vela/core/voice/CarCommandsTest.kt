package app.vela.core.voice

import app.vela.core.voice.CarCommands.Command
import org.junit.Assert.assertEquals
import org.junit.Test

class CarCommandsTest {

    @Test fun `navigate home in all its forms`() {
        listOf("navigate home", "Navigate home.", "take me home", "go home", "drive to my house", "home").forEach {
            assertEquals(it, Command.GoHome, CarCommands.parse(it))
        }
    }

    @Test fun `work variants`() {
        listOf("navigate to work", "go to my office", "work").forEach {
            assertEquals(it, Command.GoWork, CarCommands.parse(it))
        }
    }

    @Test fun `find my car`() {
        listOf("find my car", "Where's my car?", "where did I park").forEach {
            assertEquals(it, Command.FindMyCar, CarCommands.parse(it))
        }
    }

    @Test fun `mute unmute end`() {
        assertEquals(Command.Mute, CarCommands.parse("Mute"))
        assertEquals(Command.Mute, CarCommands.parse("be quiet"))
        assertEquals(Command.Unmute, CarCommands.parse("unmute"))
        assertEquals(Command.EndNav, CarCommands.parse("end navigation"))
        assertEquals(Command.EndNav, CarCommands.parse("Stop the navigation."))
    }

    @Test fun `verb stripped from a destination search`() {
        assertEquals(Command.Search("the nearest gas station"), CarCommands.parse("navigate to the nearest gas station"))
        assertEquals(Command.Search("central park"), CarCommands.parse("take me to central park"))
        assertEquals(Command.Search("starbucks"), CarCommands.parse("directions to starbucks"))
    }

    @Test fun `plain searches pass through with original casing`() {
        assertEquals(Command.Search("Coffee shops near me"), CarCommands.parse("Coffee shops near me"))
        assertEquals(Command.Search("Madis Coffee Roasters"), CarCommands.parse("Madis Coffee Roasters"))
    }

    @Test fun `a place merely CONTAINING a keyword is not a command`() {
        // "Home Depot" must stay a search - only an exact keyword (after verb stripping) commands.
        assertEquals(Command.Search("Home Depot"), CarCommands.parse("Home Depot"))
        assertEquals(Command.Search("workshop cafe"), CarCommands.parse("navigate to workshop cafe"))
        assertEquals(Command.Search("Mute Swan Pub"), CarCommands.parse("Mute Swan Pub"))
    }

    @Test fun `home as a destination of a nav verb still commands`() {
        assertEquals(Command.GoHome, CarCommands.parse("navigate to home"))
        assertEquals(Command.GoWork, CarCommands.parse("drive to the office"))
    }
}
