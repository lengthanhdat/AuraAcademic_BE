from pymongo import MongoClient
import sys

# Ensure UTF-8 output on Windows
sys.stdout.reconfigure(encoding='utf-8')

client = MongoClient('mongodb://localhost:27017/')
db = client['aura_academic']
collection = db['exams']

print("--- ALL TEMPLATES ---")
templates = list(collection.find({"isTemplate": True}))
print(f"Found {len(templates)} templates:")
for t in templates:
    print(f"- ID: {t.get('_id')}, Title: {t.get('title')}, TeacherId: {t.get('teacherId')}")

print("\n--- ALL EXAMS ---")
exams = list(collection.find())
print(f"Total exams in DB: {len(exams)}")
for e in exams[:10]:
    print(f"- ID: {e.get('_id')}, Title: {e.get('title')}, isTemplate: {e.get('isTemplate')}, isPractice: {e.get('isPractice')}, isBankItem: {e.get('isBankItem')}, TeacherId: {e.get('teacherId')}")
