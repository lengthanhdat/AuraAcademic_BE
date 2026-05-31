from pymongo import MongoClient

client = MongoClient('mongodb://localhost:27017/')
db = client['aura_academic']
collection = db['ai_jobs']

# Find all DONE jobs
jobs = collection.find({"status": "DONE"}, sort=[("createdAt", -1)])

found_images = False
for job in jobs:
    images = job.get('extractedImages', [])
    if len(images) > 0:
        found_images = True
        print(f"Job ID: {job['_id']} has {len(images)} images")
        questions = job.get('questions', [])
        for q in questions:
            if '[IMG_' in q.get('text', ''):
                print(f"  Question contains image tag: {q['text'].encode('utf-8', 'ignore').decode('utf-8')}")
        for i, img in enumerate(images):
            has_newline = '\n' in img or '\r' in img
            print(f"  Image {i}: Length {len(img)}, Starts with: {img[:20]}, Has newline: {has_newline}")
        break

if not found_images:
    print("No DONE jobs with images found in the database.")
