# Questions, live information and spoken answers

Cactus AI answers more than phone commands: the time, the date, battery and storage, arithmetic, sports, the weather and
general questions. Needle picks the tool; Kotlin code produces the answer; the phone's own text-to-speech reads it.

**No Ollama, no Rust, no cloud AI service, no API key, and no web server of ours.** Web answers come from a Google results
page in **your own browser**, read through the Accessibility service you switch on yourself. Whistle (speech to text) and
Needle (command understanding) still run entirely on the phone, and no audio is ever sent anywhere.

## 1. Where an answer comes from

| Kind of question | Tool Needle picks | Answered by | Leaves the phone? |
| --- | --- | --- | --- |
| "What time is it?", "what time is it in California?", "what day is tomorrow?", "how many days until Halloween?" | `get_time`, `get_date` | `java.time` with the phone's clock and time zone | **No** |
| "How much battery is left?", "is it charging?", "Android version?", "how much storage?" | `check_battery`, `device_info` | public Android APIs, no permission | **No** |
| "What is 15 percent of 240?", "twelve times eleven" | `calculate` | a small safe parser (no code is ever evaluated) | **No** |
| "When do the Tigers play?", "did the Lions win?", "NFL scores" | `sports_info` | a Google search in your browser, read back, then **verified** (section 4) | Only the words of the search, sent by your browser |
| "What's the weather?" | `weather_info` | same | same |
| "Who invented the telephone?" | `answer_question` | same | same |
| "Search Google for ..." | `search_web` | same, with the results read to you | same |

A question the phone can answer by itself is **never sent to the web**, even if Needle chose the online tool: a plain-code
check (`LocalGuard`) re-routes "what time is it" and the like to the local answer first. Time, date, battery, storage and
calculations work in airplane mode.

The tool descriptions given to Needle are short, and the whole tool list shrinks in steps if Needle's context window refuses it
(shorter wording first, then drop screen control, then the online questions, then the local ones; the original phone actions are
never dropped). The Models tab says if anything had to be dropped. Whether Needle chooses well among 24 tools is **unmeasured**
(section 8).

## 2. Local answers

* **Time and date**: IANA zones and well-known place names ("California" is Pacific time; places that span zones say so),
  daylight saving applied, the phone's 12 or 24-hour setting, relative days (today, tomorrow, yesterday, next Friday), counts
  to holidays (computed, including Easter) and to dates. An impossible date such as February 30 gets a plain reply.
* **Device**: battery percent and charging state (and AC/USB/wireless), Android version and security patch, free and total
  storage, phone model. A value Android does not give is reported as unavailable, never guessed.
* **Arithmetic**: numbers and number words, percent, powers and brackets, with decimal arithmetic. Anything else is refused
  instead of evaluated.

## 3. Reading a browser's results (Short Answer and Read Results)

When a question needs the web, the app opens `https://www.google.com/search?q=...` (a fixed address; only your question's
words are put in) in your browser, waits for it to load, and the Accessibility service reads the visible text of that window.
Then Cactus AI comes back to the front and answers.

1. **What is read**: titles (headings), descriptions, the visible address, and a featured answer or a scoreboard card if the
   page has one. Browser buttons, the address bar and tabs are left out when the page is inside a web view; fields you type
   into, password and payment fields, and banking-style apps are never read.
2. **What is dropped**: advertisements, menus and filters, "people also ask" and "related searches" boxes, cookie notices, and
   any line that looks like an email address, phone number, long number, card number, or an address/credential phrase.
3. **Two modes** (Settings, "Spoken answers"):
   * **Short Answer**: one quoted sentence, only when at least two **different websites** agree on the same name or number.
     Otherwise it falls back to reading the first two or three results.
   * **Read Results**: the titles and descriptions of the first two or three relevant results.
4. **Never read**: whole pages, web addresses character by character (the host is said as a word, such as "espn dot com"),
   or more than about 900 characters in one answer.
5. **The page stays untrusted**: text from the web is shown and spoken only. It is never given to Needle and never turned
   into an action; the code that handles it cannot call the command or action code (a test checks the imports), so a web page
   that says "open the camera" does nothing.
6. **If the page can't be read** (Accessibility off, the browser hides its content, nothing loaded in about 14 s): the results are
   left on the screen and the answer says reading is unavailable. If the phone is offline, nothing is opened.
7. **Coming back**: the service asks Android to bring Cactus AI forward; if Android refuses a background start, it presses Back
   once; if that does not work the browser stays where it is. This is best effort and **must be checked on the phone**.

The answer card always says **where it came from** ("Answered on this phone", or "Online · checked 3:42 PM") and whether it
was independently confirmed. Sources are listed as rows you can tap to open.

## 4. Sports

NFL, MLB, NHL, NBA, college football and college basketball. Team names, nicknames and abbreviations are resolved
(`TeamResolver`); your favourite teams in Settings decide an ambiguous "Tigers"; college words ("Michigan football") pick a
school; when two leagues have a team of that name and nothing decides, Cactus AI asks which one instead of guessing. "Today",
"tomorrow", "yesterday" and "next" use your phone's time zone. The structure allows a dedicated sports API to be added later as
a `SportsDataProvider`; none is configured.

What it says, and when:

| Situation | Rule |
| --- | --- |
| **Completed game** | A final score is stated as fact only if **two different websites** (the scoreboard card on the page counts as one) give the same teams, score and date. Otherwise it reports what was found and says it could not be confirmed. |
| **Upcoming game** | A date and time are stated only if two different sites agree and the time carries a time zone, then converted to yours. |
| **Game in progress** | A live score from search snippets is **never** presented as current. The answer says a live score cannot be verified from the web and shows where to look. (A fresh official structured feed, if added, can be.) |
| **Anything unclear** | "I couldn't verify that." Scores and start times are never invented, and a missing field is left out rather than filled. |
| **Off-season, or an assumed team** | Said plainly ("I assumed you meant the Detroit Tigers"). |

Each answer includes the opponent and the date, and when it was checked.

## 5. Weather

The temperature sentence is quoted from the page (not computed), converted to Fahrenheit or Celsius per your setting (or your
region), and attributed to its site. With no place in the question it uses "Home city" from Settings, or asks for one.

## 6. Spoken answers

Android's `TextToSpeech`, with your phone's chosen engine and voice (offline voices are listed first), adjustable speed and pitch,
a voice picker and a Test voice button, and a **Stop speaking** button on the Home, Command and assistant screens.

* Answers are spoken automatically when "Speak answers automatically" is on (default on); "Allow the assistant to speak" turns
  all speech off.
* Audio focus is requested (other audio ducks) and given back when speech ends, is stopped, fails or loses focus.
* **The assistant's own voice never becomes your next command**: the microphone only opens when you tap it; tapping it first
  cuts off any speech and waits a moment for it to die away; and nothing is spoken while the microphone is open.
* A caution that goes with an answer ("not checked", "I assumed...") is spoken with it.

## 7. Settings that matter here

Listening (auto stop, silence time, longest recording, sensitivity), Running commands (automatic execution, threshold, show
confidence, cancel window), Spoken answers, Questions and the web (look things up online, read search results, temperature,
home city, favourite teams), Recent commands. **Look things up online** off means no browser is opened for questions; local
answers still work.

## 8. What is tested, and what is not

Run in CI (`:core` JVM tests, **synthetic fixtures**, 389 tests in all): the time, date, device and calculator answers; the
local-first re-routing; the tool schemas and the shrinking tool list; the result-page parser and the sensitive-text filter; team
resolution; the sports parser and the verifier (including stale, unavailable and unverifiable live cases and time zones); the
question and weather answerers; the browser-search polling and fallbacks (accessibility off, offline, unreadable page, own
window ignored, cancellation); the speech-session lifecycle (start-up, queueing, focus, stop, replacement, errors, microphone
guard); the architecture rules (web text cannot reach the command code; no cloud AI endpoint or key in shipped code).

**Not tested without the Pixel 7**, and unknown until you run it: what Chrome's accessibility tree really contains for a Google
results page, whether headings and link addresses are exposed, whether the parser's rules hold on the live page layout
(Google changes it without notice), whether Android lets the app come back to the front, how the system voice sounds, and
how well Needle chooses between the 24 tools. The checklist (sections 10 to 15) lists the exact steps and phrases.
