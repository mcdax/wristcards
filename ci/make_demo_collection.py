"""Builds a neutral demo collection (no real cards) for screenshots and the emulator end-to-end tests.
Usage: python -I make_demo_collection.py <output collection.anki2>"""
import sys, os
from anki.collection import Collection

path = sys.argv[1]
if os.path.exists(path):
    os.remove(path)
col = Collection(path)
basic = col.models.by_name("Basic") or col.models.by_name("Einfach")
cloze = col.models.by_name("Cloze") or col.models.by_name("Lückentext")

def deck(name):
    return col.decks.id(name)

def add(model, did, fields, tags=()):
    n = col.new_note(model)
    for i, v in enumerate(fields):
        n.fields[i] = v
    n.tags = list(tags)
    col.add_note(n, did)

es = deck("Languages::Spanish")
for q, a in [("the apple", "la manzana"), ("to learn", "aprender"), ("the library", "la biblioteca"),
             ("tomorrow", "mañana"), ("the bridge", "el puente"), ("to remember", "recordar"),
             ("the watch", "el reloj"), ("always", "siempre")]:
    add(basic, es, [q, f"<b>{a}</b>"])
fr = deck("Languages::French")
for q, a in [("the cat", "le chat"), ("good morning", "bonjour"), ("the book", "le livre")]:
    add(basic, fr, [q, f"<b>{a}</b>"])

geo = deck("Geography::Capitals")
for c, cap in [("Portugal", "Lisbon"), ("Canada", "Ottawa"), ("Australia", "Canberra"), ("Kenya", "Nairobi"),
               ("Japan", "Tokyo"), ("Chile", "Santiago"), ("Norway", "Oslo"), ("Vietnam", "Hanoi")]:
    add(basic, geo, [f"Capital of <b>{c}</b>?", cap])

bio = deck("Science::Biology")
for t in ["The {{c1::mitochondria}} is the powerhouse of the cell.",
          "Plants convert light into chemical energy through {{c1::photosynthesis}}.",
          "DNA is made of four bases: adenine, thymine, {{c1::guanine}} and cytosine.",
          "The human heart has {{c1::four}} chambers."]:
    add(cloze, bio, [t, ""])

sci = deck("Science::Physics")
add(basic, sci, ["Name the three laws of motion: inertia · acceleration · action and reaction.",
                 "Isaac Newton, <i>Principia</i> (1687)<hr id=extra>• An object stays at rest unless a force acts on it<br>• F = m · a<br>• Every action has an equal and opposite reaction"])
add(basic, sci, ["Speed of light in vacuum?", "≈ 300,000 km/s"])

# Make a few cards due already so the numbers look mixed.
for cid in list(col.find_cards("deck:Languages::Spanish"))[:3] + list(col.find_cards("deck:Geography::Capitals"))[:4]:
    card = col.get_card(cid)
    col.sched.set_due_date([cid], "0")
col.close()
print("ok")
