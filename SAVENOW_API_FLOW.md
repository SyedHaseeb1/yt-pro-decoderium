# SaveNow.to API - Complete Download Flow

## Overview

SaveNow provides a complete video download API with a 4-step process to download YouTube videos in various formats.

## Step 1: Load Available Formats

**Request:**
```
GET https://p.savenow.to/api/card2/?url={ENCODED_VIDEO_URL}
```

**Example:**
```bash
curl 'https://p.savenow.to/api/card2/?url=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3D116CoKXIiWg'
```

**Response:**
Returns HTML page containing:
- Video title
- Video thumbnail
- Format options as JavaScript array
- Download button

**Format Options Available:**
```
Video:  360, 480, 720, 1080, 1440, 4k, 8k
Audio:  mp3, m4a, aac, flac, ogg, opus, wav, webm
```

Each format object contains:
```json
{
  "key": "720",
  "label": "MP4",
  "quality": "720p"
}
```

**What to Extract:**
- Video title
- Available formats list
- Thumbnail URL

---

## Step 2: Request Download (Get Task ID)

**Request:**
```
GET https://p.savenow.to/api/v2/download?button=1&format={FORMAT}&url={ENCODED_VIDEO_URL}&iframe_source={ORIGIN}
```

**Parameters:**
- `button`: Always `1`
- `format`: Selected format key (e.g., "720", "mp3", "4k")
- `url`: Encoded YouTube video URL
- `iframe_source`: Origin/domain info (can be empty or "youtube.com")

**Example:**
```bash
curl 'https://p.savenow.to/api/v2/download?button=1&format=720&url=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3D116CoKXIiWg&iframe_source=youtube.com'
```

**Response:**
```json
{
  "success": true,
  "id": "v2_stream_d183c44c2b3323aeb2c7",
  "progress_url": "https://p.savenow.to/api/progress?id=v2_stream_d183c44c2b3323aeb2c7",
  "text": "Preparing streaming download",
  "title": "Layla | لیلی 🔥 Arabic Techno House Mix Deep Oriental Beats & Energy",
  "info": {
    "title": "Layla | لیلی 🔥 Arabic Techno House Mix Deep Oriental Beats & Energy",
    "image": "https://i.ytimg.com/vi/116CoKXIiWg/hqdefault.jpg"
  },
  "format": "720",
  "full_format": "mp4 [720p]",
  "thumbnail_url": "https://i.ytimg.com/vi/116CoKXIiWg/hqdefault.jpg"
}
```

**Extract:**
- `id`: Task ID for polling
- `progress_url`: Endpoint to check progress

---

## Step 3: Poll Progress Until Download Ready

**Request:**
```
GET {progress_url}
```

OR

```
GET https://p.savenow.to/api/progress?id={TASK_ID}
```

**Example:**
```bash
curl 'https://p.savenow.to/api/progress?id=v2_stream_d183c44c2b3323aeb2c7'
```

**Response (In Progress):**
```json
{
  "success": 0,
  "progress": 500,
  "text": "Processing...",
  "title": "Layla | لیلی 🔥 Arabic Techno House Mix Deep Oriental Beats & Energy"
}
```

**Response (Ready):**
```json
{
  "success": 1,
  "progress": 1000,
  "download_url": "https://aiden90.savenow.to/api/v2/download/4sULClW765fGpKUkcNyWZ0IeL7btD0wVnSf2g78mD3h0Zjxt",
  "text": "Finished",
  "title": "Layla | لیلی 🔥 Arabic Techno House Mix Deep Oriental Beats & Energy",
  "format": "720",
  "full_format": "mp4 [720p]"
}
```

**Polling Logic:**
1. Check if `success == 1`
2. If not ready, wait 1-2 seconds
3. Poll again
4. Repeat until ready (max 30 seconds)

**Extract when ready:**
- `download_url`: Actual downloadable file link

---

## Step 4: Download the File

**Request:**
```
GET {download_url}
```

**Example:**
```bash
curl -I 'https://aiden90.savenow.to/api/v2/download/4sULClW765fGpKUkcNyWZ0IeL7btD0wVnSf2g78mD3h0Zjxt'
```

**Response Headers:**
```
HTTP/2 200
content-type: video/mp4
content-disposition: attachment; filename="Layla_Arabic_Techno_House_Mix_720p.mp4"
```

**File Download:**
- Browser/system downloads file
- Filename comes from `content-disposition` header
- Uses actual video title as filename
- Direct file stream with proper MIME type

---

## Complete Implementation Flow

```
┌─────────────────────────────────────────────────────────────┐
│ Step 1: Load Formats                                        │
│ GET /api/card2/?url={VIDEO_URL}                            │
│ Extract: format options, title, thumbnail                  │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
         ┌───────────────────────┐
         │ Show Format Selection │
         │ User selects format   │
         └───────────┬───────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ Step 2: Request Download                                    │
│ GET /api/v2/download?format={FORMAT}&url={URL}&button=1    │
│ Extract: id, progress_url                                   │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ Step 3: Poll Progress                                       │
│ GET {progress_url}                                          │
│ Loop until: success == 1                                    │
│ Extract: download_url                                       │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ Step 4: Download File                                       │
│ GET {download_url}                                          │
│ Save file with filename from content-disposition header     │
└─────────────────────────────────────────────────────────────┘
```

## Key Points

1. **All steps required** - Cannot skip format loading
2. **Task ID based** - Each download request gets unique ID
3. **Polling required** - Download preparation takes time (1-5 seconds)
4. **Timeout needed** - Set max poll time (30 seconds recommended)
5. **Filename from API** - Content-disposition header provides actual filename
6. **Format selection** - User must choose format before processing
7. **Video title preserved** - Actual YouTube title used as filename

## Error Handling

**Format Loading Fails:**
- Check internet connection
- Verify video URL is valid
- Verify SaveNow service is accessible

**Download Request Fails:**
- Invalid format key
- Invalid video URL
- SaveNow service error

**Progress Poll Fails/Timeout:**
- Network interruption
- Processing takes too long
- SaveNow service error

**Download Fails:**
- Network interruption
- Disk space issue
- Download URL expired (get new one)

## API Endpoints Summary

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/card2/` | GET | Load formats |
| `/api/v2/download` | GET | Request download task |
| `/api/progress` | GET | Check download status |
| `/api/v2/download/{TOKEN}` | GET | Download actual file |

## Response Codes

- `200 OK` - Request successful
- `405 Method Not Allowed` - Wrong HTTP method
- `429 Too Many Requests` - Rate limited
- `500 Server Error` - SaveNow service error
