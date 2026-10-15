#!/usr/bin/env python3
"""
Builds the studio's icon library (backend/src/main/resources/icons/) from two permissively licensed sets:
  * Tabler Icons (MIT)  - line icons, rasterised white-on-transparent so the renderer can tint them to any theme
  * Fluent UI Emoji (MIT, Microsoft) - colourful 3D emoji for kids lessons and general knowledge
Needs: pip install cairosvg pillow. Run from the repo root:  python3 tools/build_icon_library.py
Names that do not exist upstream are skipped and listed at the end, so the lists below can be edited freely.
"""
import concurrent.futures as cf, io, json, os, re, sys, urllib.parse, urllib.request
import cairosvg
from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "backend", "src", "main", "resources", "icons")
TABLER = "https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/outline/%s.svg"
FLUENT = "https://raw.githubusercontent.com/microsoft/fluentui-emoji/main/assets/%s/3D/%s_3d.png"
FLUENT_SKIN = "https://raw.githubusercontent.com/microsoft/fluentui-emoji/main/assets/%s/Default/3D/%s_3d_default.png"   # emoji with skin tones

LINE = {
 "tech": "server database cloud cloud-upload cloud-download api api-app app-window brand-docker brand-git brand-github brand-python brand-javascript brand-react brand-nodejs brand-html5 brand-css3 brand-aws brand-azure brand-google brand-android brand-apple brand-windows brand-vue brand-angular brand-typescript brand-kotlin brand-swift brand-php brand-mongodb brand-mysql brand-linkedin brand-youtube brand-instagram brand-whatsapp brand-telegram terminal-2 terminal code code-circle bug cpu device-laptop device-desktop device-mobile device-tablet device-watch device-tv device-gamepad-2 wifi router network plug-connected antenna-bars-5 bluetooth battery-charging usb qrcode barcode fingerprint lock lock-open key shield-check shield-lock certificate mail message messages send bell share link layout-grid layout-dashboard git-branch git-merge git-commit git-pull-request versions refresh rotate repeat arrows-shuffle filter search zoom-in settings adjustments tool hammer wrench robot brain sparkles wand bulb rocket target webhook binary braces brackets database-import database-export server-2 cloud-computing hierarchy-2 topology-star-3 stack-2 stack-3 packages cube cube-send file-code file-text file-zip folder folders table-column list-details json cloud-lock cpu-2 chip memory microphone-2 camera video headphones keyboard mouse printer wifi-off world-www ethernet",
 "science": "atom atom-2 flask flask-2 test-pipe microscope dna dna-2 magnet battery plug ruler ruler-measure scale thermometer temperature flame droplet wind snowflake sun moon cloud-rain cloud-storm rainbow planet satellite telescope orbit mountain tree plant plant-2 leaf flower seeding bug fish bone heartbeat stethoscope pill vaccine vaccine-bottle virus virus-search first-aid-kit ambulance eye ear tooth mood-happy mood-sad mood-smile mood-kid mood-cry radioactive recycle atom-off bacteria? brain lungs heart heart-rate-monitor wheelchair bandage microscope-off bulb-filled sunrise sunset ripple waves-sine wave-sine wave-square lamp flare solar-panel windmill bolt-filled battery-4 shield-check lighthouse beaker?",
 "maths": "calculator math math-function math-symbols math-pi math-x-plus-x percentage sum pi square-root-2 variable brackets abacus ruler-2 triangle square circle hexagon pentagon octagon diamond star hash infinity plus minus x divide equal equal-not math-greater math-lower math-integral math-integrals chart-bar chart-line chart-pie chart-histogram chart-dots chart-area angle arrows-diagonal cube-3d-sphere dice-1 dice-2 dice-3 dice-4 dice-5 dice-6 numbers number-0 number-1 number-2 number-3 number-4 number-5 number-6 number-7 number-8 number-9 sigma clock-hour-3 shape shape-2 shape-3 compass triangle-square-circle vector vector-bezier-2 axis-x axis-y",
 "business": "building-bank building-store building-skyscraper building-factory building-hospital building-warehouse cash cash-banknote credit-card wallet coin coins shopping-cart shopping-bag truck-delivery package box archive report-analytics report-money chart-candle trending-up trending-down presentation presentation-analytics briefcase id-badge-2 user users user-check user-plus user-star users-group handshake award trophy medal medal-2 flag flag-3 bookmark star heart thumb-up thumb-down check x alert-triangle info-circle help-circle clock hourglass calendar calendar-event calendar-stats world map-pin map compass route target-arrow zoom-money receipt receipt-tax discount percentage coin-rupee currency-rupee currency-dollar currency-euro currency-bitcoin pig-money gift tag tags speakerphone phone phone-call mail-opened building truck-return scale-outline timeline-event list-check checklist clipboard-check ladder",
 "education": "book books notebook pencil pen school backpack language abc alphabet-latin certificate puzzle dice chess music microphone headphones camera video photo palette brush scissors writing paperclip bulb ruler-3 pencil-minus edit eraser highlight bookmarks book-2 library blackboard presentation chalkboard school-bell old-versions bell-ringing brand-open-source world-longitude globe map-2 mountain trees campfire tent bike ball-football ball-basketball ball-volleyball ball-tennis swimming run yoga skateboarding stairs-up flag-2 confetti balloon cake candle ice-cream-2 pizza apple carrot meat egg milk bottle cup coffee tea salad soup bread cheese lemon fish-bone paw dog cat horse butterfly feather leaf-2",
 "transport": "car bus bike motorbike train plane helicopter ship sailboat rocket truck ambulance scooter walk traffic-lights road bridge parking gas-station charging-pile steering-wheel wheel engine propeller anchor",
 "nav": "arrow-up arrow-down arrow-left arrow-right arrow-big-right arrows-left-right arrows-up-down arrow-back-up arrow-forward-up arrow-curve-right arrow-fork arrow-merge arrow-rotary-right chevron-right chevrons-right player-play player-pause player-stop player-skip-forward menu-2 dots home settings-2 logout login plus-circle circle-check circle-x square-check point-filled circle-dot target-arrow focus-2 maximize minimize pin eye-off bookmark-filled",
}

EMOJI = {  # friendly name -> Fluent folder name
 "animals": {"lion":"Lion","tiger":"Tiger face","elephant":"Elephant","giraffe":"Giraffe","zebra":"Zebra","monkey":"Monkey face","panda":"Panda","koala":"Koala","bear":"Bear","dog":"Dog face","cat":"Cat face","cow":"Cow face","pig":"Pig face","mouse":"Mouse face","rabbit":"Rabbit face","fox":"Fox","frog":"Frog","penguin":"Penguin","owl":"Owl","duck":"Duck","chicken":"Chicken","chick":"Baby chick","fish":"Fish","tropical_fish":"Tropical fish","whale":"Spouting whale","dolphin":"Dolphin","octopus":"Octopus","turtle":"Turtle","snake":"Snake","butterfly":"Butterfly","bee":"Honeybee","ladybug":"Lady beetle","snail":"Snail","ant":"Ant","spider":"Spider","eagle":"Eagle","parrot":"Parrot","flamingo":"Flamingo","peacock":"Peacock","horse":"Horse face","camel":"Camel","kangaroo":"Kangaroo","hippo":"Hippopotamus","rhino":"Rhinoceros","crocodile":"Crocodile","dinosaur":"T-rex","sauropod":"Sauropod","unicorn":"Unicorn","wolf":"Wolf","deer":"Deer","squirrel":"Chipmunk","hedgehog":"Hedgehog","crab":"Crab","shark":"Shark","lobster":"Lobster","bat":"Bat","swan":"Swan","dove":"Dove","goat":"Goat","sheep":"Ewe","elephant_calf":"Mammoth"},
 "food": {"apple":"Red apple","green_apple":"Green apple","banana":"Banana","orange":"Tangerine","grapes":"Grapes","strawberry":"Strawberry","watermelon":"Watermelon","pineapple":"Pineapple","mango":"Mango","cherries":"Cherries","peach":"Peach","lemon":"Lemon","pear":"Pear","carrot":"Carrot","broccoli":"Broccoli","corn":"Ear of corn","tomato":"Tomato","potato":"Potato","onion":"Onion","garlic":"Garlic","pizza":"Pizza","burger":"Hamburger","bread":"Bread","cheese":"Cheese wedge","egg":"Egg","milk":"Glass of milk","cake":"Birthday cake","ice_cream":"Soft ice cream","cookie":"Cookie","candy":"Candy","popcorn":"Popcorn","rice":"Cooked rice","curry":"Curry rice","samosa":"Dumpling","tea":"Hot beverage","coffee":"Teacup without handle","water_bottle":"Bottle with popping cork","honey":"Honey pot","salad":"Green salad","sandwich":"Sandwich","donut":"Doughnut","chocolate":"Chocolate bar","lollipop":"Lollipop","coconut":"Coconut","avocado":"Avocado","eggplant":"Eggplant","pepper":"Hot pepper","mushroom":"Mushroom"},
 "vehicles": {"car":"Automobile","taxi":"Taxi","bus":"Bus","fire_truck":"Fire engine","ambulance":"Ambulance","police_car":"Police car","truck":"Delivery truck","bicycle":"Bicycle","scooter":"Motor scooter","airplane":"Airplane","helicopter":"Helicopter","rocket":"Rocket","sailboat":"Sailboat","ship":"Ship","train":"Locomotive","metro":"Metro","tractor":"Tractor","auto_rickshaw":"Auto rickshaw","skateboard":"Skateboard","ufo":"Flying saucer","motorcycle":"Motorcycle","speedboat":"Speedboat","bullet_train":"High-speed train","satellite":"Satellite","parachute":"Parachute"},
 "nature": {"sun":"Sun","moon":"Full moon","crescent_moon":"Crescent moon","star":"Star","glowing_star":"Glowing star","cloud":"Cloud","rainbow":"Rainbow","snowflake":"Snowflake","fire":"Fire","droplet":"Droplet","tree":"Deciduous tree","pine_tree":"Evergreen tree","palm_tree":"Palm tree","cactus":"Cactus","sunflower":"Sunflower","tulip":"Tulip","rose":"Rose","blossom":"Blossom","mushroom_red":"Mushroom","mountain":"Mountain","volcano":"Volcano","earth":"Globe showing asia-australia","earth_europe":"Globe showing europe-africa","earth_americas":"Globe showing americas","globe_meridians":"Globe with meridians","comet":"Comet","planet":"Ringed planet","lightning":"High voltage","tornado":"Tornado","wave":"Water wave","umbrella":"Umbrella with rain drops","snowman":"Snowman","leaf":"Leaf fluttering in wind","herb":"Herb","seedling":"Seedling","fallen_leaf":"Fallen leaf","four_leaf_clover":"Four leaf clover","sparkles":"Sparkles","thermometer_hot":"Hot face"},
 "shapes": {"red_circle":"Red circle","blue_circle":"Blue circle","green_circle":"Green circle","yellow_circle":"Yellow circle","orange_circle":"Orange circle","purple_circle":"Purple circle","red_square":"Red square","blue_square":"Blue square","green_square":"Green square","yellow_square":"Yellow square","orange_square":"Orange square","purple_square":"Purple square","red_heart":"Red heart","blue_heart":"Blue heart","green_heart":"Green heart","yellow_heart":"Yellow heart","orange_heart":"Orange heart","purple_heart":"Purple heart","red_triangle":"Red triangle","diamond":"Large blue diamond","orange_diamond":"Large orange diamond","check":"Check mark button","cross":"Cross mark","question":"Red question mark","exclamation":"Red exclamation mark","hundred":"Hundred points","one":"Keycap 1","two":"Keycap 2","three":"Keycap 3","four":"Keycap 4","five":"Keycap 5","six":"Keycap 6","seven":"Keycap 7","eight":"Keycap 8","nine":"Keycap 9","ten":"Keycap 10"},
 "objects": {"book":"Closed book","open_book":"Open book","books":"Books","pencil":"Pencil","backpack":"Backpack","school":"School","scissors":"Scissors","paperclip":"Paperclip","alarm_clock":"Alarm clock","light_bulb":"Light bulb","magnet":"Magnet","telescope":"Telescope","microscope":"Microscope","test_tube":"Test tube","petri_dish":"Petri dish","dna":"Dna","atom":"Atom symbol","magnifier":"Magnifying glass tilted left","compass":"Compass","music_note":"Musical note","guitar":"Guitar","drum":"Drum","trumpet":"Trumpet","paintbrush":"Paintbrush","palette":"Artist palette","soccer_ball":"Soccer ball","basketball":"Basketball","cricket":"Cricket game","trophy":"Trophy","gold_medal":"1st place medal","robot":"Robot","alien":"Alien","phone":"Mobile phone","laptop":"Laptop","television":"Television","camera":"Camera","video_camera":"Video camera","bell":"Bell","key":"Key","lock":"Locked","house":"House","hospital":"Hospital","bank":"Bank","gift":"Wrapped gift","balloon":"Balloon","party":"Party popper","crown":"Crown","money_bag":"Money bag","coin":"Coin","credit_card":"Credit card","envelope":"Envelope","package":"Package","calendar":"Calendar","clipboard":"Clipboard","bar_chart":"Bar chart","chart_up":"Chart increasing","puzzle":"Puzzle piece","dice":"Game die","chess":"Chess pawn","flag":"Triangular flag","map":"World map","ruler":"Straight ruler","triangle_ruler":"Triangular ruler","abacus":"Abacus","calculator":"Input numbers","battery":"Battery","plug":"Electric plug","gear":"Gear","wrench":"Wrench","hammer":"Hammer","toolbox":"Toolbox","syringe":"Syringe","pill":"Pill","stethoscope":"Stethoscope","bandage":"Adhesive bandage","shield":"Shield","bomb":"Bomb","candle":"Candle","hourglass":"Hourglass not done","newspaper":"Newspaper","film":"Film frames","microphone":"Microphone","headphone":"Headphone","radio":"Radio","satellite_dish":"Satellite antenna","brain":"Brain","tooth":"Tooth","eye":"Eye","ear":"Ear","bone":"Bone","heart_anatomical":"Anatomical heart","lungs":"Lungs","footprints":"Footprints"},
 "places": {"hindu_temple":"Hindu temple","mosque":"Mosque","church":"Church","castle":"Castle","statue_of_liberty":"Statue of liberty","tokyo_tower":"Tokyo tower","mount_fuji":"Mount fuji","bridge":"Bridge at night","stadium":"Stadium","factory":"Factory","office":"Office building","school_building":"School","farm":"Tractor","camping":"Camping","beach":"Beach with umbrella","desert":"Desert","island":"Desert island","national_park":"National park","city":"Cityscape","sunrise":"Sunrise","night_city":"Night with stars","tent":"Tent","construction":"Building construction","post_office":"Japanese post office","department_store":"Department store","hotel":"Hotel","airport_tower":"Control knobs"},
}

def get(url, binary=False):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "studio-icon-build"}), timeout=30) as r:
            return r.read()
    except Exception:
        return None

def line_job(group_name):
    group, name = group_name
    data = get(TABLER % name)
    if not data: return ("line", group, name, None, None)
    svg = data.decode("utf-8")
    tags = re.search(r"tags:\s*\[(.*?)\]", svg)
    tags = [t.strip() for t in tags.group(1).split(",")] if tags else []
    png = cairosvg.svg2png(bytestring=svg.replace("currentColor", "#ffffff").encode(), output_width=256, output_height=256)
    return ("line", group, name, png, tags)

def emoji_job(item):
    group, friendly, folder = item
    slug = folder.lower().replace(" ", "_")
    data = get(FLUENT % (urllib.parse.quote(folder), slug)) or get(FLUENT_SKIN % (urllib.parse.quote(folder), slug))
    if not data: return ("emoji", group, friendly, None, None)
    im = Image.open(io.BytesIO(data)).convert("RGBA").resize((256, 256), Image.LANCZOS)
    buf = io.BytesIO()
    im.quantize(colors=200, method=Image.FASTOCTREE, dither=Image.NONE).save(buf, "PNG", optimize=True)
    return ("emoji", group, friendly, buf.getvalue(), [friendly.replace("_", " ")])

def main():
    for sub in ("line", "emoji"): os.makedirs(os.path.join(OUT, sub), exist_ok=True)
    jobs_line = [(g, n.rstrip("?")) for g, names in LINE.items() for n in dict.fromkeys(names.split())]
    jobs_emoji = [(g, f, folder) for g, d in EMOJI.items() for f, folder in d.items()]
    manifest = {"line": {}, "emoji": {}}
    missing = []
    with cf.ThreadPoolExecutor(10) as ex:
        results = list(ex.map(line_job, jobs_line)) + list(ex.map(emoji_job, jobs_emoji))
    seen_line = set()
    for kind, group, name, png, tags in results:
        if png is None: missing.append(f"{kind}:{name}"); continue
        if kind == "line":
            if name in seen_line: continue
            seen_line.add(name)
        fname = name.replace("-", "_")
        open(os.path.join(OUT, kind, fname + ".png"), "wb").write(png)
        manifest[kind][fname] = {"group": group, "tags": tags[:8]}
    json.dump(manifest, open(os.path.join(OUT, "manifest.json"), "w"), indent=1, sort_keys=True)
    print("line icons:", len(manifest["line"]), " emoji:", len(manifest["emoji"]), " missing upstream:", len(missing))
    print("missing:", ", ".join(sorted(missing)))

if __name__ == "__main__":
    main()
