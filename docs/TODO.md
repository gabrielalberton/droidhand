# To-do

Pending work that is written but not done yet. Remove an item when it lands on `main`.

## Pop-up and split windows without ADB (branch `feat/popup-split-windows`)

Code is on the branch (commit 05e271d), builds, unit tests and MCP tests pass. **Not tested on a phone yet**, so it is
not merged: the MCP would offer agents `popup`, `split` and `window` while phones run an app that ignores them (an older app
opens `popup=true` full screen and reads/types into the foreground window instead of the one given).

What it adds:
- `launch_app popup=true` (pop-up over the lower part of the main screen, no prompt) and `split=true` (other half of split
  screen via `FLAG_ACTIVITY_LAUNCH_ADJACENT`, asks first). The result reports the window id and the real `layout`.
- `window=<id|package>` on `screen_snapshot`, `get_foreground_app`, `tap`, `long_press`, `swipe`, `scroll`, `type_text`,
  `key_event`: refs click and type without touching (the user's keyboard stays), coordinates outside the window and keys
  while it is not focused are refused. `list_windows` returns id and bounds.
- MCP `instructions`: background window first (own window when ADB can be turned on); pop-up/split only when it does not
  fit (no background window, apps that refuse it, system dialogs, the user's final step).

Test before merging (S20, Android 13 / One UI 5, and an S24, Android 17):
- [ ] `popup=true`: opens as a pop-up (`layout: popup`), user's app keeps the focus and its keyboard stays open.
- [ ] `split=true` from a service context: does One UI split (`layout: split`) or open full screen? On the S20 the
      accessibility split action already failed (docs/devices.md); record the result per phone there.
- [ ] `screen_snapshot window=<id>` reads the pop-up while the user types in another app.
- [ ] `tap ref` and `type_text ref` in the pop-up: the user's keyboard does not close, text never lands in the user's field.
- [ ] `tap x,y` outside the window → `point_outside_window`; `key_event back` while not focused → refused.
- [ ] Acting inside the agent's own pop-up does not show the main-screen prompt; `split=true` does.
- [ ] `window=<package>` when the user has the same app open full screen: the agent must not act there without the prompt.
- [ ] Then: merge, release the app, rebuild the MCP server (`npm run build`) and restart the agents' MCP.
