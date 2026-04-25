import re

def resolve_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    blocks = re.split(r'(<<<<<<< HEAD\n.*?\n=======\n.*?\n>>>>>>> origin/main\n)', content, flags=re.DOTALL)
    if len(blocks) == 1:
        print(f"No conflicts found in {filepath}")
        return

    new_content = blocks[0]
    
    for i in range(1, len(blocks), 2):
        conflict = blocks[i]
        head_match = re.search(r'<<<<<<< HEAD\n(.*?)\n=======\n', conflict, flags=re.DOTALL)
        main_match = re.search(r'=======\n(.*?)\n>>>>>>> origin/main\n', conflict, flags=re.DOTALL)
        
        head = head_match.group(1) if head_match else ""
        main = main_match.group(1) if main_match else ""
        
        # Decide how to merge based on index or content
        # For CSS/styles: combine both
        # For HTML: combine both or keep HEAD if they are just different layout of the same thing
        # Let's print out what they look like so we can resolve them.
        
        print(f"--- CONFLICT {i//2 + 1} ---")
        print("HEAD:\n", head[:100], "...\nMAIN:\n", main[:100], "...")

resolve_file('frontend/mc-activity-heatmap-v13.html')
