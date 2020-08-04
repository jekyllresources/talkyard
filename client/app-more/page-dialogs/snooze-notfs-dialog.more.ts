/*
 * Copyright (c) 2020 Kaj Magnus Lindberg
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

//------------------------------------------------------------------------------
   namespace debiki2.pagedialogs {
//------------------------------------------------------------------------------

const r = ReactDOMFactories;
var Modal = rb.Modal;
var ModalHeader = rb.ModalHeader;
var ModalTitle = rb.ModalTitle;
var ModalBody = rb.ModalBody;
var ModalFooter = rb.ModalFooter;


let dialog;

type Callback = (_: any) => void;
let dialogSetState: (_: [any]) => void;

export function openSnoozeDialog(me: Myself) {
  if (!dialog) {
    dialog = ReactDOM.render(SnoozeDialog(), utils.makeMountNode());  // or [use_portal] ?
  }
  dialogSetState([me]);
}


const SnoozeDialog = React.createFactory<{}>(function() {
  const [diagState, setDiagState] = React.useState<[Myself]>(null);
  const [hours, setHours] = React.useState<number>(4);

  dialogSetState = setDiagState;

  const me: Myself | U = diagState && diagState[0];
  const isOpen = !!me;
  const isSnoozing = me && me_isSnoozing(me);

  function close() {
    setDiagState(null);
  }

  function toggleSnooze() {
    ReactActions.snoozeUntilMins(isSnoozing ? false : getNowMins() + hours * 60);
    close();
  }

  const modalHeader = isOpen &&
      ModalHeader({},
        ModalTitle({},
          isSnoozing ? "Stop snoozing?" : "Snooze notifications?"));  // I18N

  const modalBody = isOpen && (!isSnoozing
      ? ModalBody({},
          r.p({}, "Stop email notifications, for how many hours?"),   // I18N
          Input({ type: 'number',
              value: hours,
              onChange: (event) => setHours(event.target.value) }),
          PrimaryButton({ onClick: toggleSnooze }, "Snooze"))   // I18N
      : ModalBody({},
          r.p({}, "Start receiving email notifications again?"),   // I18N
          PrimaryButton({ onClick: toggleSnooze }, "Yes, stop snoozing"))   // I18N
      );
      

  const modalFooter = isOpen &&
      ModalFooter({},
        Button({ onClick: close }, t.Cancel));


  return (
      Modal({ show: isOpen, onHide: close, dialogClassName: 's_SnzD' },
        modalHeader,
        modalBody,
        modalFooter,
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
