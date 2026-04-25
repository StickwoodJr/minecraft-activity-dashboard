import re
import sys

def resolve(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    blocks = re.split(r'(<<<<<<< HEAD\n.*?\n=======\n.*?\n>>>>>>> origin/main\n)', content, flags=re.DOTALL)
    if len(blocks) == 1:
        print("No conflicts found")
        return

    new_content = blocks[0]

    for i in range(1, len(blocks), 2):
        conflict = blocks[i]
        head_match = re.search(r'<<<<<<< HEAD\n(.*?)=======\n', conflict, flags=re.DOTALL)
        main_match = re.search(r'=======\n(.*?)>>>>>>> origin/main\n', conflict, flags=re.DOTALL)
        
        head = head_match.group(1) if head_match else ""
        main = main_match.group(1) if main_match else ""
        
        # We need to manually decide based on the index.
        # Let's print out what they look like, or just write them to a file for manual review.
        print(f"Conflict {i//2 + 1} Lengths: HEAD={len(head)}, MAIN={len(main)}")
        
        # For now, let's keep HEAD because it has the feature we just added. 
        # But main has cosmetics! We can keep both in CSS, but in HTML maybe HEAD?
        # This requires manual logic. Let's just output the parts to investigate.

resolve('frontend/mc-activity-heatmap-v13.html')
