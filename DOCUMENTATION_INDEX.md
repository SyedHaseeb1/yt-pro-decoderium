# Documentation Index

Complete guide to YTPro documentation. Start here!

## 🎯 Quick Navigation

### I'm New Here
**→ Start with**: `DEVELOPER_GUIDE.md` (10 min read)
- Understand the 4-layer system
- Learn the download flow
- See code locations for common tasks

### I Need to Fix a Bug
**→ Start with**: `ARCHITECTURE.md` → Section matching the bug
- State diagram (bottom) shows possible states
- File hierarchy shows which file to check
- Thread model shows if it's concurrency issue

### I Need to Modify Something
**→ Start with**: `API_REFERENCE.md` → Find the component
- See exact parameters & responses
- Understand error cases
- Check Java implementation examples

### I Want to Understand Everything
**→ Read in order**:
1. `DEVELOPER_GUIDE.md` - Big picture (10 min)
2. `ARCHITECTURE.md` - Detailed design (30 min)
3. `API_REFERENCE.md` - API details (20 min)

---

## 📚 Documentation Map

```
├── README.md
│   └── Project overview & features
│
├── DOCUMENTATION_INDEX.md ← You are here
│   └── Navigation guide
│
├── DEVELOPER_GUIDE.md (⭐ START HERE)
│   ├─ 4-layer system explanation
│   ├─ Simple download flow
│   ├─ Key files to know
│   ├─ Common tasks
│   ├─ Debugging tips
│   ├─ Common problems & fixes
│   └─ Testing guide
│
├── ARCHITECTURE.md (🔍 DETAILED DESIGN)
│   ├─ Complete file hierarchy
│   ├─ Download flow with diagram
│   ├─ Core components (4 major files)
│   ├─ Data models
│   ├─ Threading model
│   ├─ Error handling strategy
│   ├─ Performance characteristics
│   └─ State diagram
│
└── API_REFERENCE.md (📡 ALL APIS)
    ├─ SaveNow.to API (3 endpoints)
    ├─ DownloadManager API
    ├─ MediaMuxerUtils API
    ├─ Data models with fields
    ├─ Error codes & messages
    ├─ Thread safety info
    ├─ Rate limiting
    └─ Testing examples
```

---

## 🎓 Learning Paths

### Path 1: "I want to understand the codebase"
1. Read: `DEVELOPER_GUIDE.md` - Get the big picture
2. Read: `ARCHITECTURE.md` - Understand each component
3. Open code: Follow file paths mentioned in both docs
4. Try: Make the modifications in "Common Tasks" section

**Time**: 1-2 hours
**Outcome**: Understand how download system works

---

### Path 2: "I need to fix a bug in download system"
1. Read: `DEVELOPER_GUIDE.md` - "Common Problems & Solutions"
2. Find your problem in the list
3. Read: `ARCHITECTURE.md` - Check the file mentioned
4. Read: `API_REFERENCE.md` - Understand API expectations
5. Fix: Make code changes based on understanding
6. Test: Follow testing guide in `DEVELOPER_GUIDE.md`

**Time**: 30 min - 1 hour depending on bug
**Outcome**: Bug fixed, codebase understood

---

### Path 3: "I need to add new feature"
1. Read: `DEVELOPER_GUIDE.md` - Understand current flow
2. Read: `ARCHITECTURE.md` - See how data flows through layers
3. Decide: Which layer should handle new feature?
   - UI changes? → Modify `SaveNowDownloadDialog`
   - API changes? → Modify `SaveNowApiClient`
   - Download changes? → Modify `DownloadManager`
   - Codec changes? → Modify `MediaMuxerUtils`
4. Read: `API_REFERENCE.md` - Understand data models
5. Implement: Add feature in appropriate layer
6. Test: Verify all layers still work together

**Time**: 1-2 hours for simple feature
**Outcome**: Feature added without breaking existing code

---

## 📋 File Reference Quick Lookup

### Need to work on: UI / Dialog
**File**: `downloader/SaveNowDownloadDialog.java`
**Read**: `ARCHITECTURE.md` → Core Components → SaveNowDownloadDialog

### Need to work on: API Communication
**File**: `downloader/SaveNowApiClient.java`
**Read**: `API_REFERENCE.md` → SaveNow.to API

### Need to work on: Download Progress
**File**: `downloader/DownloadManager.java`
**Read**: `ARCHITECTURE.md` → Core Components → DownloadManager

### Need to work on: Video Format Compatibility
**File**: `utils/MediaMuxerUtils.java`
**Read**: `ARCHITECTURE.md` → Core Components → MediaMuxerUtils

### Need to work on: Error Messages
**File**: `downloader/SaveNowDownloadDialog.java` (lines 545-620)
**Read**: `DEVELOPER_GUIDE.md` → Task 4: Custom Error Message

### Need to work on: Polling Configuration
**File**: `downloader/SaveNowDownloadDialog.java` (lines 31-32)
**Read**: `DEVELOPER_GUIDE.md` → Task 2: Change Polling Timeout

---

## 🔗 Cross-References

### By Topic

**Download Flow**:
- `DEVELOPER_GUIDE.md` - Simple overview
- `ARCHITECTURE.md` - Data Flow Diagram section
- `ARCHITECTURE.md` - State Diagram section

**Error Handling**:
- `DEVELOPER_GUIDE.md` - Debugging Tips
- `DEVELOPER_GUIDE.md` - Common Problems & Solutions
- `ARCHITECTURE.md` - Error Handling Strategy section
- `API_REFERENCE.md` - Error Codes & Messages section

**Codec Support**:
- `ARCHITECTURE.md` - Core Components → MediaMuxerUtils
- `API_REFERENCE.md` - Data Models section
- `DEVELOPER_GUIDE.md` - Task 3: Support New Codec

**Threading**:
- `ARCHITECTURE.md` - Threading Model section
- `API_REFERENCE.md` - Thread Safety section
- `DEVELOPER_GUIDE.md` - Code Style Guide → Thread Safety

**Testing**:
- `DEVELOPER_GUIDE.md` - Testing the Download System
- `API_REFERENCE.md` - Testing section

---

## 💡 Tips for Efficient Learning

1. **Don't memorize**: Reference docs as you code
2. **Code follows docs**: If something doesn't match, docs need update
3. **Logs are clues**: When stuck, add logging and check logcat
4. **One layer at a time**: Understand layers independently, then together
5. **Test incrementally**: Test each layer separately before full flow

---

## 🚨 When You're Stuck

**Problem**: "I don't understand how X works"
1. Find X in file hierarchy (ARCHITECTURE.md)
2. Read the component description
3. Look at Java implementation details
4. Check API_REFERENCE for data models

**Problem**: "Code changed and docs are wrong"
1. Fix the code to match the documented design (if possible)
2. Update the docs to match the new code (if design changed)
3. Never leave docs out of sync with code

**Problem**: "I want to change behavior"
1. Read ARCHITECTURE.md - understand current flow
2. Decide which layer to change
3. Check API_REFERENCE for affected data models
4. Read DEVELOPER_GUIDE - check if it's already a "common task"
5. Make change, test thoroughly

---

## 📞 Documentation Maintenance

These docs are kept up-to-date with the code:

- **DEVELOPER_GUIDE.md** - Updated when common tasks change
- **ARCHITECTURE.md** - Updated when file structure or data flow changes
- **API_REFERENCE.md** - Updated when APIs change
- **This file** - Updated when doc structure changes

**If you find inconsistencies**: The code is the source of truth. Docs should match code, not vice versa.

---

## 🎯 Success Metrics

You've successfully understood YTPro when you can:

1. ✅ Explain the 4-layer system without reading docs
2. ✅ Find which file to edit for any feature/bug
3. ✅ Understand why data flows through 4 layers
4. ✅ Add basic feature without breaking existing code
5. ✅ Debug issues using logs and understanding flow

---

**Last Updated**: 2026-08-17
**Documentation Status**: Current & Comprehensive
**Audience**: Android developers (all experience levels)

---

## Quick Links

- [Developer Guide](DEVELOPER_GUIDE.md) - 10 min read to get started
- [Architecture](ARCHITECTURE.md) - Detailed system design
- [API Reference](API_REFERENCE.md) - All APIs documented
- [README](README.md) - Project overview
