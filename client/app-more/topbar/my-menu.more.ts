/*
 * Copyright (c) 2014-2017 Kaj Magnus Lindberg
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

/// <reference path="../more-prelude.more.ts" />
/// <reference path="../notification/Notification.more.ts" />
/// <reference path="../page-dialogs/view-as-dialog.more.ts" />

//------------------------------------------------------------------------------
   namespace debiki2.topbar {
//------------------------------------------------------------------------------

const r = ReactDOMFactories;



export const MyMenuContent = createFactory({
  displayName: 'MyMenuContent',

  markNotfsRead: function() {
    Server.markNotfsRead();
  },

  onLogoutClick: function() {
    // Sometimes the logout button is a bit close to the close-menu X button,
    // so better ask, in case clicked by accident.
    util.openDefaultStupidDialog({
      dialogClassName: 'e_ByeD',
      tiny: true,
      body: t.LogOut + '?',
      primaryButtonTitle: t.YesBye,
      secondaryButonTitle: t.Cancel,
      onCloseOk: function(whichButton) {
        if (whichButton === 1) {
          debiki2.ReactActions.logout();
        }
      }
    });
  },

  viewAsOther: function() {
    pagedialogs.openViewAsDialog();
  },

  render: function() {
    let menuContent;
    const store: Store = this.props.store;
    const me: Myself = store.me;

    // ------- Staff link, notfs, help

    const urgentReviewTasks = makeNotfIcon('reviewUrgent', me.numUrgentReviewTasks);
    const otherReviewTasks = makeNotfIcon('reviewOther', me.numOtherReviewTasks);
    const adminMenuItem = !isStaff(me) ? null :
      MenuItemLink({ to: linkToAdminPage(), className: 'esMyMenu_admin' },
        r.span({ className: 'icon-settings' }, t.Admin));
    const reviewMenuItem = !urgentReviewTasks && !otherReviewTasks ? null :
      MenuItemLink({ to: linkToReviewPage(), id: 'e2eMM_Review' },
        t.mm.NeedsReview, urgentReviewTasks, otherReviewTasks);

    const adminHelpLink = !isStaff(me) ? null :
      MenuItemLink({ to: externalLinkToAdminHelp(), target: '_blank',
          className: 'esMyMenu_adminHelp' },
        r.span({}, me.isAdmin ? t.mm.AdminHelp : t.mm.StaffHelp,
          r.span({ className: 'icon-link-ext' })));

    // ------- Personal notf icons

    const notfsDivider = me.notifications.length ? MenuItemDivider() : null;
    const showMarkAsReadButton = me.numTalkToOthersNotfs + me.numTalkToMeNotfs >= 1;
    const hasAnyNotfs = me.notifications.length;
    const viewAllNotfsOrClear = !me.notifications.length ? null :
        MenuItemsMany({ className: 's_MM_NotfsBs' },
          hasAnyNotfs && LinkUnstyled({ to: linkToUsersNotfs(me.username) }, t.mm.MoreNotfs),
          LinkUnstyled({ onClick: () => pagedialogs.openSnoozeDialog(me), className: 's_MM_SnzB' },
              me_isSnoozing(me) ? "Stop snoozing" : (  // I18N
                  // If "View all notfs" visible, then, the user can guess that Snooze is
                  // for snoozing notifications?
                  hasAnyNotfs ? "Snooze" : "Snooze notifications")),  // I18N
          showMarkAsReadButton &&
            LinkUnstyled({ onClick: this.markNotfsRead, className: 'e_DismNotfs' }, t.mm.DismNotfs));
    // Nice to have a View All button at the bottom of the notifications list too, if it's long,
    // especially on mobile so won't need to scroll up.
    const extraViewAllNotfsOrClear = me.notifications.length <= 10 ? null :
        viewAllNotfsOrClear;
    const notfsItems = me.notifications.map((notf: Notification) =>
        MenuItemLink({ key: notf.id, to: linkToNotificationSource(notf),
            className: notf.seen ? '' : 'esNotf-unseen' },
          notification.Notification({ notification: notf })));
    // UX COULD if !me.thereAreMoreUnseenNotfs, show a message "No more unread notifications" ?

    // ------- Stop impersonating

    let isViewingAsHint;
    let stopImpersonatingMenuItem;
    let notYourMenuHint;
    let impersonationStuffDivider;
    let viewAsOtherItem;

    if (store.isImpersonating) {
      isViewingAsHint = store.isViewingAs
          ? "You're viewing this site as someone else."
          : "You're impersonating another user.";
      isViewingAsHint = r.p({ className: 's_MM_ImpInfo' }, isViewingAsHint);
      stopImpersonatingMenuItem = !store.isImpersonating ? null :
          MenuItem({ onClick: Server.stopImpersonatingReloadPage, className: 's_MM_StopImpB' },
            // dupl text [306MKTW33]
            store.isViewingAs ? "Stop viewing as other" : "Stop impersonating");
      if (me.isLoggedIn) {
        notYourMenuHint = r.div({className: 's_MM_ImpNotYour'},
          "The menu items below are for that other user.");
        impersonationStuffDivider = MenuItemDivider();
      }
    }
    else if (isStaff(me)) {
      viewAsOtherItem =
          MenuItem({ onClick: this.viewAsOther, className: 's_MM_ViewAsB' },
            "View this site as ...");
    }

    // ------- The current user

    let viewProfileLogOutMenuItem;
    let viewUsersOrGroups;
    let viewDraftsAndBookmarks;
    let myStuffDivider;
    let unhideHelpMenuItem;
    if (me.isLoggedIn) {
      // If is admin, show the logout button on the same line as the "View profile" link,
      // because then there're admin links above, and lots of space for the  X close-menu button.
      // Otherwise, show the logout button on the same line as the "Drafts..." button,
      // so it won't overlap with the X close-menu button.
      const logoutButtonNextToViewProfile = isStaff(me);
      const logoutButton = LinkUnstyled({ onClick: this.onLogoutClick, id: 'e2eMM_Logout' }, t.mm.LogOut);

      viewProfileLogOutMenuItem =
          MenuItemsMany({},
            LinkUnstyled({ to: linkToMyProfilePage(store), id: 'e2eMM_Profile' }, t.mm.ViewProfile),
            logoutButtonNextToViewProfile ? logoutButton : null);

      const isMemberOfCustomGroup = _.some(me.myGroupIds, id => id >= LowestAuthenticatedUserId);
      viewUsersOrGroups = !isStaff(me) && !isMemberOfCustomGroup ? null :
          MenuItemsMany({},
            LinkUnstyled({ to: GroupsRoot, id: 'te_VwGrps' }, t.mm.ViewGroups)),

      viewDraftsAndBookmarks =
          MenuItemsMany({},
            LinkUnstyled({ to: linkToMyDraftsEtc(store), className: 'e_MyDfsB' },
              t.mm.DraftsEtc),
            !logoutButtonNextToViewProfile ? logoutButton : null);

      myStuffDivider = MenuItemDivider();

      unhideHelpMenuItem =
        MenuItem({ onClick: ReactActions.showHelpMessagesAgain },
          r.span({ className: 'icon-help' }, t.mm.UnhideHelp))
    }

    // ------- The menu

    menuContent =
      r.ul({ className: 'dropdown-menu' },
        isViewingAsHint,
        stopImpersonatingMenuItem,
        notYourMenuHint,
        impersonationStuffDivider,
        adminMenuItem,
        adminHelpLink,
        reviewMenuItem,
        (adminMenuItem || reviewMenuItem) ? MenuItemDivider() : null,
        viewProfileLogOutMenuItem,
        viewUsersOrGroups,
        viewDraftsAndBookmarks,
        viewAsOtherItem,
        notfsDivider,
        viewAllNotfsOrClear,
        notfsItems,
        extraViewAllNotfsOrClear,
        myStuffDivider,
        unhideHelpMenuItem);

    return menuContent;
  }
});


function makeNotfIcon(type: string, number: number) {
  if (!number) return null;
  const numMax99 = Math.min(99, number);
  const wideClass = number >= 10 ? ' esNotfIcon-wide' : '';
  return r.div({ className: 'esNotfIcon esNotfIcon-' + type + wideClass}, numMax99);
}


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=tcqwn list
