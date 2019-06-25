/**
 * Copyright (c) 2015-2019 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package talkyard.server.backup

import com.debiki.core.Prelude._
import com.debiki.core._
import debiki.EdHttp._
import debiki.SpecialContentPages
import debiki.dao.{PagePartsDao, SiteDao}
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import scala.collection.mutable


case class SiteBackupImporterExporter(globals: debiki.Globals) {  RENAME // to SiteDumpImporter ?


  def upsertIntoExistingSite(siteId: SiteId, siteData: SiteBackup, browserIdData: BrowserIdData) {
    dieIf(siteData.site.map(_.id) isSomethingButNot siteId, "TyE35HKSE")
    val dao = globals.siteDao(siteId)
    dao.readWriteTransaction { tx =>

      // Posts link to pages, and Question type pages links to the accepted answer post,
      // that is, can form a cycle.  And a root category links to the section index page,
      // which links to the root category.
      tx.deferConstraints()

      // Real id = an id to something in the database.
      //
      // External import id = the external id (in some external software system)
      // of something we're inserting or updating.
      //
      // Temp import ids and nrs = ids and nrs > 2e9 things in the siteData use
      // to link to each other. These ids are then remapped to low values, like 1, 2, 3, 4,
      // before actually inserting into the database. Exactly which ids and nrs we'll
      // use, depend on what's in the db already — we need to avoid conflicts.


      // ----- Page ids
      //
      // Start with remapping temporary import ids to real page ids that don't
      // conflict with any existing pages, or are the same as already existing
      // pages if the imported page(s) have matching external import ids.
      // — Start with pages, because other things, like posts and categories,
      // link to pages (posts are placed on a page, and root categories have
      // a section page id).
      //
      // Don't insert the pages here though — we haven't remapped the page's
      // category id or any answer post id temp import id, to real ids, yet.

      val oldPagesByExtImpId: Map[ExtImpId, PageMeta] =
        tx.loadPageMetasByExtImpIdAsMap(siteData.pages.flatMap(_.extImpId))

      val pageRealIdsByTempImpId = mutable.HashMap[PageId, PageId]()

      def remappedPageTempId(tempId: PageId): PageId = {
        if (!isPageTempId(tempId)) tempId
        else {
          pageRealIdsByTempImpId.getOrElse(tempId, throwBadRequest(
            "TyE5DKGWT205", s"Page with temp id $tempId missing from the uploaded data"))
        }
      }

      siteData.pages foreach { pageTempId: PageMeta =>
        val extImpId = pageTempId.extImpId getOrElse throwForbidden(
          "TyE305KBSG", "Inserting pages with no extImpId not yet impl")
        val tempId = pageTempId.pageId
        val realId = oldPagesByExtImpId.get(extImpId).map(oldPage => {
          throwBadRequestIf(!isPageTempId(tempId) && tempId != oldPage.pageId,
            "TyE30TKKWFG3", o"""Imported page w extImpId '$extImpId' has real id $tempId
               which differs from page ${oldPage.pageId} in the db, with the same extImpId
               """)
          oldPage.pageId
        }).getOrElse({
          tx.nextPageId()
        })
        pageRealIdsByTempImpId.put(pageTempId.pageId, realId)
      }


      // ----- Participants

      val extImpIds =
        siteData.guests.flatMap(_.extImpId)
        // ++ siteData.users.flatMap(_.extImpId)  later
        // ++ siteData.groups.flatMap(_.extImpId)  later

      // If there're participants in the database with the same external ids
      // as some of those in the siteData, then, they are to be updated, and we
      // won't create new participants, for them.
      val oldParticipantsByExtImpId: Map[ExtImpId, ParticipantInclDetails] =
        tx.loadParticipantsInclDetailsByExtImpIdsAsMap(extImpIds)

      val ppsWithRealIdsByTempImpId = mutable.HashMap[UserId, ParticipantInclDetails]()
      // Later: if some guests have real ids already, lookup any existing users
      // with those same real ids — then either we'll do nothing, or update
      // those already existing users.  (3607TK2)

      def remappedPpTempId(tempId: UserId): UserId = {
        if (-LowestTempImpId < tempId && tempId < LowestTempImpId) {
          // The import data specifies a real id for an already existing user.
          // We didn't need to remap it; just return it.
          tempId
        }
        else {
          // Either 1) there was already a participant in the db with the same external id
          // as [the user we're importing with id = tempId], and hen already has a real id.
          // Or 2) we're inserting a new user and have assigned it a new real id.
          val anyPpWithRealId = ppsWithRealIdsByTempImpId.get(tempId)
          val ppWithRealId: ParticipantInclDetails = anyPpWithRealId.getOrElse({
            throwBadRequest("TyE305KRD3H", o"""Participant with temp id $tempId
            missing from the uploaded data""")
          })
          dieIf(ppWithRealId.id <= -LowestTempImpId || LowestTempImpId <= ppWithRealId.id, "TyE305KRST2")
          ppWithRealId.id
        }
      }

      val firstNextGuestId = tx.nextGuestId
      var nextGuestId = firstNextGuestId

      siteData.guests foreach { guestTempId: Guest =>
        // For now, don't allow upserting via real ids, only via ext imp ids. (3607TK2)
        throwForbiddenIf(guestTempId.id > -LowestTempImpId,
          "TyE05KKST25", s"Upserting guest with real id ${guestTempId.id}: not yet implemented")

        val upsertedGuestRealId = guestTempId.extImpId.flatMap(oldParticipantsByExtImpId.get) match {
          case None =>
            val guestRealId = guestTempId.copy(id = nextGuestId)
            nextGuestId -= 1
            tx.insertGuest(guestRealId)
            guestRealId
          case Some(oldGuestRealId: Guest) =>
            dieIf(oldGuestRealId.id <= -LowestTempImpId, "TyE046MKP01")
            dieIf(oldGuestRealId.extImpId != guestTempId.extImpId, "TyE046MKP02")
            //if (guestTempId.updatedAt.millis > oldGuestRealId.updatedAt.millis)
            //  val guestRealId = guestTempId.copy(id = oldGuestRealId.id)
            //  tx.updateGuest(guestRealId)
            //  guestRealId
            //else
            oldGuestRealId
        }
        dieIf(upsertedGuestRealId.id <= -LowestTempImpId,
          "TyE305HKSD2", s"Guest id ${guestTempId.id} got remapped to ${upsertedGuestRealId.id}")
        ppsWithRealIdsByTempImpId.put(guestTempId.id, upsertedGuestRealId)
      }


      // ----- Posts

      val oldPostsByExtImpId = tx.loadPostsByExtImpIdAsMap(siteData.posts.flatMap(_.extImpId))
      val oldPostsByPagePostNr = mutable.HashMap[PagePostNr, Post]()

      val firstNextPostId = tx.nextPostId()
      var nextPostId = firstNextPostId

      val postsRealByTempId = mutable.HashMap[PostId, Post]()
      val postsRealByTempPagePostNr = mutable.HashMap[PagePostNr, Post]()

      val pageNumBumpsByRealPageId = mutable.HashMap[PageId, PageMetaNumBumps]()

      siteData.posts.groupBy(_.pageId).foreach { case (tempPageId, tempPosts) =>
        val realPageId = remappedPageTempId(tempPageId)
        val allOldPostsOnPage = tx.loadPostsOnPage(realPageId)  ; COULD_OPTIMIZE // don't need them all
        allOldPostsOnPage foreach { oldPost =>
          oldPostsByPagePostNr.put(oldPost.pagePostNr, oldPost)
        }
        val firstNextReplyNr =
          if (allOldPostsOnPage.isEmpty) FirstReplyNr
          else allOldPostsOnPage.map(_.nr).max + 1
        var nextReplyNr = firstNextReplyNr
        dieIf(nextReplyNr < FirstReplyNr, "TyE05HKGJ5")

        val postTempIdsToInsert = mutable.HashSet[PostId]()

        var pageMetaNumBumps = PageMetaNumBumps()

        val oldAndNewPosts: Seq[Post] = tempPosts map { tempPost =>
          val realPostExclParentNr: Post = tempPost.extImpId.flatMap(oldPostsByExtImpId.get) match {
            case Some(oldPostRealIdNr: Post) =>
              // Later: If has same id and nr, then could upsert.
              // If differetn id or nr, then, error?
              oldPostRealIdNr
            case None =>
              // Probably we need to remap the post nr to 2, 3, 4, 5 ... instead of a temp nr.
              // Unless has a real nr already, e.g. the title or body post nr.
              val maybeNewRealNr =
                if (tempPost.nr < LowestTempImpId) tempPost.nr
                else { nextReplyNr += 1 ; nextReplyNr - 1 }

              oldPostsByPagePostNr.get(PagePostNr(realPageId, maybeNewRealNr)) match {
                case Some(oldPostSamePostNr: Post) =>
                  // Do nothing. The old post should be an already existing page title
                  // or body. Later, maybe update. [IMPUPD]
                  // This happens e.g. if we import old Disqus comments, to a page for which
                  // there's already a Talkyard embedded comments discussion. Then we can
                  // leave the already existing title and body as is.)
                  dieIf(!PageParts.isArticleOrTitlePostNr(maybeNewRealNr),
                    "TyE502BKGD8", o"""Conflict when upserting post w real pageId $realPageId
                    postNr $maybeNewRealNr and temp pageId $tempPageId postNr ${tempPost.nr}""")
                  oldPostSamePostNr
                case None =>
                  def ifThenIncr(test: Boolean, num: Int) = if (test) num + 1 else num

                  val postNewIdNr: Post = tempPost.copy(
                    pageId = realPageId,
                    id = nextPostId,
                    nr = maybeNewRealNr,
                    // parentNr — updated below
                    // later: multireplyPostNrs
                    createdById = remappedPpTempId(tempPost.createdById),
                    currentRevisionById = remappedPpTempId(tempPost.currentRevisionById),
                    approvedById = tempPost.approvedById.map(remappedPpTempId),
                    lastApprovedEditById = tempPost.lastApprovedEditById.map(remappedPpTempId),
                    collapsedById = tempPost.collapsedById.map(remappedPpTempId),
                    closedById = tempPost.closedById.map(remappedPpTempId),
                    bodyHiddenById = tempPost.bodyHiddenById.map(remappedPpTempId),
                    deletedById = tempPost.deletedById.map(remappedPpTempId))

                  pageMetaNumBumps = pageMetaNumBumps.copy(
                    //bumpedAt = When.fromMillis(math.max(
                    //  pageMetaNumBumps.bumpedAt.millis, postNewIdNr.createdAtMillis)),
                    numRepliesVisible = ifThenIncr(
                      tempPost.isReply && tempPost.isSomeVersionApproved,
                      pageMetaNumBumps.numRepliesVisible),
                    numRepliesTotal = ifThenIncr(
                      tempPost.isReply,
                      pageMetaNumBumps.numRepliesTotal),
                    numPostsTotal =
                      pageMetaNumBumps.numPostsTotal + 1,
                    numOrigPostRepliesVisible = ifThenIncr(
                      tempPost.isOrigPostReply,
                      pageMetaNumBumps.numPostsTotal))

                  nextPostId += 1
                  postTempIdsToInsert += tempPost.id
                  postNewIdNr
              }
          }

          postsRealByTempId.put(tempPost.id, realPostExclParentNr)
          postsRealByTempPagePostNr.put(tempPost.pagePostNr, realPostExclParentNr)
          realPostExclParentNr
        }

        val anyOldPageMeta = tx.loadPageMeta(realPageId)

        val anyPageCreator: Option[UserId] = anyOldPageMeta.map(_.authorId) orElse {
          oldAndNewPosts.find(_.nr == BodyNr).map(_.createdById)
        }
        val anyLastPost = maxOptBy(oldAndNewPosts.filter(_.isVisible))(_.createdAtMillis)
        val anyLastAuthor = anyLastPost.map(_.createdById)

        val frequentPosters = PageParts.findFrequentPosters(
          oldAndNewPosts, ignoreIds = anyPageCreator.toSet ++ anyLastAuthor.toSet)

        val lastVisibleReply = PageParts.lastVisibleReply(oldAndNewPosts)

        pageNumBumpsByRealPageId.put(realPageId,
          pageMetaNumBumps.copy(
            lastApprovedReplyAt = lastVisibleReply.map(_.createdAt),
            lastApprovedReplyById = lastVisibleReply.map(_.createdById),
            frequentPosterIds = frequentPosters))

        // Update parent nrs, sanitize html, and upsert into db.
        tempPosts foreach { tempPost =>
          if (postTempIdsToInsert.contains(tempPost.id)) {
            val postTempParentNr = postsRealByTempPagePostNr.getOrElse(tempPost.pagePostNr,
              throwBadRequest(
                "TyE305KRTD3", s"Parent post ${tempPost.pagePostNr} not found in site data"))
            dieIf(postTempParentNr.parentNr != tempPost.parentNr, "TyE306RKTJ2")

            val postRealNoHtml =
              if (tempPost.parentNr.isEmpty) {
                postTempParentNr
              }
              else {
                val parentPagePostNr = tempPost.pagePostNr.copy(postNr = postTempParentNr.parentNr.get)
                val parentPost = postsRealByTempPagePostNr.getOrElse(parentPagePostNr, throwBadRequest(
                  "TyE6AKD025", s"Parent post missing, temp page post nr $parentPagePostNr"))
                postTempParentNr.copy(parentNr = Some(parentPost.nr))
              }

            // These put() not needed; do anyway, to update parentNr and reduce future bug risk.
            postsRealByTempId.put(tempPost.id, postRealNoHtml)
            postsRealByTempPagePostNr.put(tempPost.pagePostNr, postRealNoHtml)

            // Sanitize html or convert from commonmark to html — good to wati with,
            // until we're here, so we know the imported contents seems well structured?
            // Need a way to specify if the source is in commonmark or html?
            val postReal =
              if (postRealNoHtml.approvedSource.isEmpty) postRealNoHtml
              else {
                postRealNoHtml.copy(
                  approvedHtmlSanitized = Some(Jsoup.clean(
                    postRealNoHtml.approvedSource.get, Whitelist.basicWithImages)))
              }
            tx.insertPost(postReal)
          }
        }
      }


      // ----- Categories

      val firstNextCategoryId = tx.nextCategoryId()
      var nextCategoryId = firstNextCategoryId

      val oldCategoriesById = tx.loadCategoryMap()
      val oldCategories = oldCategoriesById.values

      val categoriesRealIdsByTempImpId = mutable.HashMap[CategoryId, CategoryId]()

      def remappedCategoryTempId(tempId: CategoryId): CategoryId = {
        if (tempId < LowestTempImpId) tempId
        else {
          categoriesRealIdsByTempImpId.getOrElse(tempId, throwBadRequest(
            "TyE7KF026HR", s"Category with temp id $tempId missing from the uploaded data"))
        }
      }

      // Remap ids.
      siteData.categories foreach { catTempId: Category =>
        val extImpId = catTempId.extImpId getOrElse throwForbidden(
          "TyE6DKWG2RJ", s"Inserting categories with no extImpId not yet impl, category: $catTempId")
        val realId = oldCategories.find(_.extImpId is extImpId).map(oldCatRealId => {
          throwBadRequestIf(catTempId.id < FirstTempImpId && catTempId.id != oldCatRealId,
            "TyE306HKD2", o"""Category to import with real id ${catTempId.id} has the same
            extImpId as category ${oldCatRealId.id} — but they aren't the same;
            they have different ids""")
          oldCatRealId.id
        }) getOrElse {
          if (catTempId.id < FirstTempImpId) {
            // Could update the already existing category? But what if it has a different
            // extImpId? Or if it has none, when the one getting imported does? or the
            // other way around? — For now, just disallow this.
            // oldCategoriesById.get(catTempId.id) — maybe later.
            throwForbidden("TyE305HKRD6",
              s"Importing categories with real ids not yet implemented, category: ${catTempId}")
          }
          nextCategoryId += 1
          nextCategoryId - 1
        }
        categoriesRealIdsByTempImpId.put(catTempId.id, realId)
      }

      // Upsert categories.
      siteData.categories foreach { catTempId: Category =>
        val realId = remappedCategoryTempId(catTempId.id)
        val oldCat = oldCategoriesById.get(realId)
        val alreadyExists = oldCat.isDefined
        if (!alreadyExists) {
          val catRealIds = catTempId.copy(
            sectionPageId = remappedPageTempId(catTempId.sectionPageId),
            parentId = catTempId.parentId.map(remappedCategoryTempId),
            defaultSubCatId = catTempId.defaultSubCatId.map(remappedCategoryTempId))
          tx.insertCategoryMarkSectionPageStale(catRealIds)
        }
      }


      // ----- Pages

      siteData.pages foreach { pageMetaTempId: PageMeta =>
        // Later: update with any reassigned participant and post ids:
        //   answerPostId (complicated? need assign tempId —> real id to posts first, somewhere above)
        val realId = pageRealIdsByTempImpId.get(pageMetaTempId.pageId) getOrDie "TyE06DKWD24"
        val pageMetaNumBumps = pageNumBumpsByRealPageId.getOrElse(realId, PageMetaNumBumps())
        def bumpNums(pageMeta: PageMeta): PageMeta = {
          val b = pageMetaNumBumps
          pageMeta.copy(
            updatedAt = tx.now.toJavaDate,
            //publishedAt = ???,
            bumpedAt = When.anyJavaDateLatestOf(pageMeta.bumpedAt, b.lastApprovedReplyAt),
            lastApprovedReplyAt = b.lastApprovedReplyAt,
            lastApprovedReplyById = b.lastApprovedReplyById,
            frequentPosterIds = b.frequentPosterIds,
            numLikes = pageMeta.numLikes + b.numLikes,
            numWrongs = pageMeta.numWrongs + b.numWrongs,
            numBurys = pageMeta.numBurys + b.numBurys,
            numUnwanteds = pageMeta.numUnwanteds + b.numUnwanteds,
            numRepliesVisible = pageMeta.numRepliesVisible + b.numRepliesVisible,
            numRepliesTotal = pageMeta.numRepliesTotal + b.numRepliesTotal,
            numPostsTotal = pageMeta.numPostsTotal + b.numPostsTotal,
            numOrigPostLikeVotes = pageMeta.numOrigPostLikeVotes + b.numOrigPostLikeVotes,
            numOrigPostWrongVotes = pageMeta.numOrigPostWrongVotes + b.numOrigPostWrongVotes,
            numOrigPostBuryVotes = pageMeta.numOrigPostBuryVotes + b.numOrigPostBuryVotes,
            numOrigPostUnwantedVotes = pageMeta.numOrigPostUnwantedVotes + b.numOrigPostUnwantedVotes,
            numOrigPostRepliesVisible = pageMeta.numOrigPostRepliesVisible + b.numOrigPostRepliesVisible)
        }

        pageMetaTempId.extImpId.flatMap(oldPagesByExtImpId.get) match {
          case None =>
            val pageMetaRealIds = pageMetaTempId.copy(
              pageId = realId,
              categoryId = pageMetaTempId.categoryId.map(remappedCategoryTempId),
              authorId = remappedPpTempId(pageMetaTempId.authorId),
              lastApprovedReplyById = pageMetaTempId.lastApprovedReplyById.map(remappedPpTempId),
              lastApprovedReplyAt = ???,  // <———
              frequentPosterIds = pageMetaTempId.frequentPosterIds.map(remappedPpTempId))
            val pageMetaOkNums = bumpNums(pageMetaRealIds)
            tx.insertPageMetaMarkSectionPageStale(pageMetaOkNums, isImporting = true)
          case Some(oldPageMeta) =>
            val pageMetaOkNums = bumpNums(oldPageMeta)
            if (pageMetaOkNums != oldPageMeta) {
              tx.updatePageMeta(pageMetaOkNums, oldMeta = oldPageMeta, markSectionPageStale = true)
            }
            /* Later?:
            if (oldPageMeta.updatedAt.getTime < pageMetaTempIds.updatedAt.getTime) {
              val pageWithId = pageMetaTempIds.copy(pageId = oldPageMeta.pageId)
              tx.updatePageMeta(pageWithId, oldMeta = oldPageMeta,
                // Maybe not always needed:
                markSectionPageStale = true)
            } */
        }
      }


      // ----- Page paths

      val oldPathsByPageTempId: Map[PageId, Seq[PagePathWithId]] = {
        val pageTempIds = siteData.pagePaths.map(_.pageId)
        val realIds: Seq[PageId] = pageTempIds.map(remappedPageTempId)
        val pathsByRealIds: Map[PageId, Seq[PagePathWithId]] =
          realIds.flatMap(tx.lookupPagePathAndRedirects).groupBy(_.pageId)
        Map(pageTempIds.flatMap(tempId => {
          val realId: PageId = remappedPageTempId(tempId)
          pathsByRealIds.get(realId).map(
            (pathsRealId: Seq[PagePathWithId]) => tempId -> pathsRealId)
        }): _*)
      }

      siteData.pagePaths foreach { pathTempId: PagePathWithId =>
        oldPathsByPageTempId.get(pathTempId.pageId) match {
          case None =>
            val pathRealId = pathTempId.copy(pageId = remappedPageTempId(pathTempId.pageId))
            tx.insertPagePath(pathRealId)
            pathRealId
          case Some(_ /* pathRealId */) =>
            // Later, could update.
        }
      }


      /* ----- Posts

      siteData.posts foreach { postTempAll: Post =>
        val oldPostSameExtImpId = postTempAll.extImpId.flatMap(oldPagesByExtImpId.get)
        if (oldPostSameExtImpId.isDefined) {
          // We've imported this external post already. For now, don't import again,
          // just do nothing. Later: Could update [IMPUPD] the old post, depending
          // on import flags like onConflict = DoNothing / UpdateAlways / UpdateIfNewer.
        }
        else {
          val postTempPageId = postsRealIdNrsByTempId.get(postTempAll.id).getOrDie("TyE5FKBG025")
          val tempPageId = postTempPageId.pageId
          val realPageId = remappedPageTempId(postTempPageId.pageId)
          val realPostNr = postTempPageId.nr
          val oldPostSamePostNr = oldPostsByPagePostNr.get(PagePostNr(realPageId, postTempPageId.nr))
          if (oldPostSamePostNr.isDefined) {
            // Do nothing. The old post should be an already existing page title
            // or body. Later, maybe update. [IMPUPD]
            //
            // (Details: There's an old post that doesn't have the same ext imp id, so it's not
            // the same as the one we're importing. Still it has the same page id and
            // post nr — although we've remapped the post nrs of the posts we're importing,
            // to avoid conflicts. But we don't remap the title and body post nrs,
            // which means we must be importing the title or body, and there's already
            // a title or body in the db, for this page. This happens e.g. if we import
            // old Disqus comments, to a page for which there's already a Talkyard
            // embedded comments discussion. Then we can leave the already existing title
            // and body untouched.)
            dieIf(!PageParts.isArticleOrTitlePostNr(realPostNr),
              "TyE502BKGD8", o"""Conflict when upserting post w real pageId $realPageId
              postNr $realPostNr and temp pageId $tempPageId postNr ${postTempAll.nr}""")
          }
          else {
            val parentPagePostNr = postTempPageId.parentNr.map { nr =>
              val parentTempPageIdPostNr = PagePostNr(tempPageId, nr)
              remappedPostNr(parentTempPageIdPostNr)
            }
            val postRealAll = postTempPageId.copy(
              pageId = realPageId,
              parentNr = parentPagePostNr,
              // later: multireplyPostNrs
              createdById = remappedPpTempId(postTempPageId.createdById),
              currentRevisionById = remappedPpTempId(postTempPageId.currentRevisionById),
              approvedById = postTempPageId.approvedById.map(remappedPpTempId),
              lastApprovedEditById = postTempPageId.lastApprovedEditById.map(remappedPpTempId),
              collapsedById = postTempPageId.collapsedById.map(remappedPpTempId),
              closedById = postTempPageId.closedById.map(remappedPpTempId),
              bodyHiddenById = postTempPageId.bodyHiddenById.map(remappedPpTempId),
              deletedById = postTempPageId.deletedById.map(remappedPpTempId))

            tx.insertPost(postRealAll)
          }
        }

        // TODO:
        // [readlater] Index post too; insert it into the index queue. And update this test: [2WBKP05].
      }  */
    }
  }


  def importCreateSite(siteData: SiteBackup, browserIdData: BrowserIdData, deleteOldSite: Boolean)
        : Site = {
    for (page <- siteData.pages) {
      val path = siteData.pagePaths.find(_.pageId == page.pageId)
      // Special pages shouldn't be reachable via any page path. Others, should.
      if (SpecialContentPages.isSpecialPageId(page.pageId)) {
        throwBadRequestIf(path.isDefined,
          "TyE2ABKY7", s"Special page has PagePath: $path")
      }
      else {
        throwBadRequestIf(path.isEmpty,
          "TyE5GKY2", s"No PagePath included for page id '${page.pageId}'")
      }
    }

    def isMissing(what: Option[Option[Any]]) = what.isEmpty || what.get.isEmpty || {
      what.get.get match {
        case s: String => s.trim.isEmpty
        case _ => false
      }
    }

    val siteToSave = siteData.site.getOrDie("TyE7KRUGV24")
    val siteSettings = siteData.settings.getOrDie("TyE5KRYTG02")

    throwForbiddenIf(isMissing(siteSettings.orgFullName),
      "EdE7KB4W5", "No organization name specified")

    // COULD do this in the same transaction as the one below — then, would need a function
    // `transaction.continueWithSiteId(zzz)`?
    val site = globals.systemDao.createAdditionalSite(
      siteToSave.pubId,
      siteToSave.name,
      siteToSave.status,
      siteToSave.canonicalHostname.map(_.hostname),
      embeddingSiteUrl = None,
      organizationName = "Dummy organization name [EsM8YKWP3]",  // fix later
      creatorId = SystemUserId,
      browserIdData = browserIdData,
      isTestSiteOkayToDelete = true,
      skipMaxSitesCheck = true,
      deleteOldSite = deleteOldSite,
      pricePlan = "Unknown",  // [4GKU024S]
      createdFromSiteId = None)

    val newDao = globals.siteDao(site.id)

    HACK // not inserting groups, only updating summary email interval. [7FKB4Q1]
    // And in the wrong transaction :-/
    newDao.saveAboutGroupPrefs(AboutGroupPrefs(
      groupId = Group.EveryoneId,
      fullName = Some("Everyone"),
      username = "everyone",
      summaryEmailIntervalMins = Some(siteData.summaryEmailIntervalMins),
      summaryEmailIfActive = Some(siteData.summaryEmailIfActive)), Who.System)

    newDao.readWriteTransaction { transaction =>
      // We might import a forum or a forum category, and then the categories reference the
      // forum page, and the forum page references to the root category.
      transaction.deferConstraints()

      transaction.upsertSiteSettings(siteSettings)

      siteData.guests foreach { guest: Guest =>
        transaction.insertGuest(guest)
      }

      siteData.users foreach { user =>
        transaction.insertMember(user)
        // [readlater] import page notf prefs [2ABKS03R]
        // [readlater] export & import username usages & emails, later. For now, create new here.
        user.primaryEmailInfo.foreach(transaction.insertUserEmailAddress)
        transaction.insertUsernameUsage(UsernameUsage(
          usernameLowercase = user.usernameLowercase, // [CANONUN]
          inUseFrom = transaction.now, userId = user.id))
        // [readlater] export & import UserStats. For now, create new "empty" here.
        transaction.upsertUserStats(UserStats.forNewUser(user.id, firstSeenAt = transaction.now,
          emailedAt = None))
        newDao.joinGloballyPinnedChats(user.briefUser, transaction)
      }
      siteData.pages foreach { pageMeta =>
        //val newId = transaction.nextPageId()
        transaction.insertPageMetaMarkSectionPageStale(pageMeta, isImporting = true)
      }
      siteData.pagePaths foreach { path =>
        transaction.insertPagePath(path)
      }
      siteData.categories foreach { categoryMeta =>
        //val newId = transaction.nextCategoryId()
        transaction.insertCategoryMarkSectionPageStale(categoryMeta)
      }
      siteData.posts foreach { post =>
        //val newId = transaction.nextPostId()
        transaction.insertPost(post)
        // [readlater] Index post too; insert it into the index queue. And update this test: [2WBKP05].
      }
      siteData.permsOnPages foreach { permission =>
        transaction.insertPermsOnPages(permission)
      }
      // Or will this be a bit slow? Kind of loads everything we just imported.
      siteData.pages foreach { pageMeta =>
        // [readlater] export & import page views too, otherwise page popularity here will be wrong.
        val pagePartsDao = PagePartsDao(pageMeta.pageId, transaction)
        newDao.updatePagePopularity(pagePartsDao, transaction)
        // For now: (e2e tests: page metas imported before posts, and page meta reply counts = wrong)
        val numReplies = pagePartsDao.allPosts.count(_.isReply)
        val correctMeta = pageMeta.copy(
          numRepliesVisible = numReplies,
          numRepliesTotal = numReplies,
          numPostsTotal = pagePartsDao.numPostsTotal)
        transaction.updatePageMeta(correctMeta, oldMeta = pageMeta, markSectionPageStale = true)
      }
    }

    site
  }

}

