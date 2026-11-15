package com.retrsoft.bilisponsorskip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryTitleIdentityTest {
    class Detail(private val bvid: String, private val cid: Long) {
        fun getBvid() = bvid
        fun getAid() = 1L
        fun getCid() = cid
    }

    interface Binding {
        fun getData(): Detail?
        fun isActive(): Boolean
    }

    class Controller(var payload: Detail?, var active: Boolean = true) : Binding {
        override fun getData() = payload
        override fun isActive() = active
    }

    open class Title(private var binding: Binding?) {
        fun rebind(next: Binding?) { binding = next }
    }
    class InheritedTitle(binding: Binding) : Title(binding)
    class AmbiguousTitle(private val first: Binding, private val second: Binding)
    interface LandscapeBinding { fun getData(): Detail? }
    class LandscapeTitle(private val binding: LandscapeBinding)

    private val ad = Detail("BV1SYht6GEs7", 2)
    private val clean = Detail("BV1SYht6GEs7", 3)

    @Test fun resolvesCurrentItemAndRejectsInactivePreload() {
        assertEquals(ResolvedVideoIdentity("BV1SYht6GEs7", "2"),
            resolveStoryTitleIdentity(Title(Controller(ad)), true))
        assertNull(resolveStoryTitleIdentity(Title(Controller(ad, false)), true))
    }

    @Test fun recycledTitleFollowsNewCidEvenWhenBvidIsUnchanged() {
        val title = Title(Controller(ad))
        assertEquals("2", resolveStoryTitleIdentity(title, true)?.cid)
        title.rebind(Controller(clean))
        assertEquals("3", resolveStoryTitleIdentity(title, true)?.cid)
        title.rebind(null)
        assertNull(resolveStoryTitleIdentity(title, true))
    }

    @Test fun observesBindingDataChangesWithoutNewWidget() {
        val controller = Controller(ad)
        val title = Title(controller)
        controller.payload = clean
        assertEquals("3", resolveStoryTitleIdentity(title, true)?.cid)
        controller.active = false
        assertNull(resolveStoryTitleIdentity(title, true))
    }

    @Test fun readsInheritedControllerField() {
        assertEquals("2", resolveStoryTitleIdentity(InheritedTitle(Controller(ad)), true)?.cid)
    }

    @Test fun rejectsMissingInvalidOrAmbiguousIdentity() {
        assertNull(resolveStoryTitleIdentity(Any(), true))
        assertNull(resolveStoryTitleIdentity(Title(Controller(null)), true))
        assertNull(resolveStoryTitleIdentity(Title(Controller(Detail("BV1SYht6GEs7", 0))), true))
        assertNull(resolveStoryTitleIdentity(AmbiguousTitle(Controller(ad), Controller(clean)), true))
    }

    @Test fun landscapeNeedsItsOwnMatchingDataButDoesNotRequirePortraitActiveMethod() {
        val title = LandscapeTitle(object : LandscapeBinding {
            override fun getData() = clean
        })
        assertEquals("3", resolveStoryTitleIdentity(title, false)?.cid)
        assertNull(resolveStoryTitleIdentity(title, true))
    }
}
