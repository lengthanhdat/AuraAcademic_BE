from pymongo import MongoClient

client = MongoClient('mongodb://localhost:27017/')
db = client['aura_academic']

print("Collections in DB:")
print(db.list_collection_names())

if 'refresh_tokens' in db.list_collection_names():
    print("\n--- REFRESH TOKENS ---")
    for t in db['refresh_tokens'].find():
        print(t)
