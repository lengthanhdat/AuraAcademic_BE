from pymongo import MongoClient

client = MongoClient('mongodb://localhost:27017/')
db = client['aura_academic']
collection = db['ai_jobs']

# Find the most recent DONE job
job = collection.find_one({"status": "DONE"}, sort=[("createdAt", -1)])

if not job:
    print("No DONE jobs found")
else:
    print(f"Job ID: {job['_id']}")
    print(f"Extracted Text length: {len(job.get('extractedText', ''))}")
    images = job.get('extractedImages', [])
    print(f"Extracted Images count: {len(images)}")
    for i, img in enumerate(images):
        print(f"  Image {i} length: {len(img)}")
    
    questions = job.get('questions', [])
    print(f"Questions count: {len(questions)}")
    for q in questions:
        if '[IMG_' in q.get('text', ''):
            print(f"  Question contains image tag: {q['text']}")
