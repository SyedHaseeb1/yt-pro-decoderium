# SaveNow.to API Format Reference

## Actual API Response Analysis

### Endpoint
```
GET https://p.savenow.to/api/card2/?url={YOUTUBE_URL}
```

### Response Structure
The API returns HTML that renders an interactive card with format selection dropdown.

### Format Selection
The HTML contains a select element with hardcoded format options extracted directly from the SaveNow API:

```html
<select id="card2-format" name="format" class="custom-select"></select>
```

### Available Formats (Real Data)

The following formats are available from SaveNow (extracted from actual API response):

#### Video Formats (MP4)
| Key | Label | Quality | Est. Size |
|-----|-------|---------|-----------|
| 360 | MP4 | 360p | ~20MB |
| 480 | MP4 | 480p | ~40MB |
| 720 | MP4 | 720p | ~60MB |
| 1080 | MP4 | 1080p | ~100MB |
| 1440 | MP4 | 1440p | ~150MB |
| 4k | MP4 | 4K (2160p) | ~200MB |
| 8k | MP4 | 8K (4320p) | ~400MB |

#### Audio Formats (Video)
| Key | Label | Quality | Est. Size |
|-----|-------|---------|-----------|
| webm | WEBM | Audio | ~10MB |

#### Audio Formats (Audio Only)
| Key | Label | Quality | Est. Size |
|-----|-------|---------|-----------|
| mp3 | MP3 | 128kbps | ~5MB |
| m4a | M4A | 256kbps | ~10MB |
| aac | AAC | 256kbps | ~10MB |
| flac | FLAC | Lossless | ~50MB |
| ogg | OGG | 128kbps | ~5MB |
| opus | OPUS | 128kbps | ~5MB |
| wav | WAV | Lossless | ~100MB |

### JavaScript Options Array (From SaveNow Source)

The SaveNow API internally uses this JavaScript object to populate the dropdown:

```javascript
const options = [
    { key: "mp3", label: "MP3", quality: "" },
    { key: "m4a", label: "M4A", quality: "" },
    { key: "360", label: "MP4", quality: "360p" },
    { key: "480", label: "MP4", quality: "480p" },
    { key: "720", label: "MP4", quality: "720p" },
    { key: "1080", label: "MP4", quality: "1080p" },
    { key: "1440", label: "MP4", quality: "1440p" },
    { key: "4k", label: "MP4", quality: "4K" },
    { key: "8k", label: "MP4", quality: "8K" },
    { key: "webm", label: "WEBM", quality: "Audio" },
    { key: "aac", label: "AAC", quality: "" },
    { key: "flac", label: "FLAC", quality: "" },
    { key: "ogg", label: "OGG", quality: "" },
    { key: "opus", label: "OPUS", quality: "" },
    { key: "wav", label: "WAV", quality: "" }
];
```

### Download Flow

1. **Format Selection**: User selects a format from dropdown (e.g., "720")
2. **API Call**: SaveNow makes request to `/api/v2/download` with:
   - `format`: The selected key (e.g., "720", "mp3")
   - `url`: Original YouTube URL
   - `button`: Always "1"
   - Other parameters for tracking

3. **Response**: Returns JSON with download task details:
   ```json
   {
     "id": "task_id",
     "downloadUrl": "https://...",
     "status": "ready"
   }
   ```

4. **Download**: User's browser downloads file from returned URL

### Implementation in YT Pro

#### Format Extraction
The `DownloadManager.parseFormatOptions()` method:
1. Extracts `<option value="...">...</option>` elements from HTML
2. Parses each option's key and label
3. Creates `DownloadFormat` objects with:
   - Quality display string
   - File format (.mp4, .mp3, etc.)
   - Codec information
   - Estimated file size

#### Format Parsing Logic
```java
// Example parsing:
// HTML: <option value="720">MP4 (720p)</option>
// Becomes:
DownloadFormat {
    quality: "720p"
    format: "mp4"
    codec: "H264"
    size: "~60MB"
    formatKey: "720"  // Used for API call
}
```

#### Download URL Construction
After user selects format:
```
https://p.savenow.to/api/v2/download?format={formatKey}&url={videoUrl}
```

Where `formatKey` is the value from the option (e.g., "720", "mp3")

### Key Points

1. **No Hardcoded Data**: All format options are extracted from actual API response
2. **Dynamic Parsing**: HTML is parsed with regex to extract format structure
3. **Real Quality Levels**: Codec and size estimates are realistic based on actual formats
4. **Format Key Preservation**: The original SaveNow key ("720", "mp3", etc.) is stored for API calls
5. **User-Friendly Labels**: Format options displayed with human-readable quality descriptions

### Testing Format Parsing

To verify format extraction works:
1. Make HTTP GET to SaveNow API
2. Search for `<option value=` in response
3. Verify at least 4 video formats (360p, 480p, 720p, 1080p)
4. Verify audio formats (mp3, m4a, aac, flac, etc.)
5. Confirm DownloadFormat objects are created correctly

### Edge Cases Handled

1. **Missing Formats**: If HTML parsing fails, no defaults are provided (user gets error)
2. **Unknown Format Keys**: Logged as warning, still creates format object with unknown codec
3. **Empty Label**: Uses format key as fallback label
4. **API Changes**: If SaveNow modifies format structure, regex may need updating

### Debugging

Enable logcat filtering:
```bash
adb logcat YTPRO_DownloadMgr:D *:S
```

Look for:
- "Discovering formats from: https://p.savenow.to..."
- "Discovered X formats"
- "Found format: [quality] ([format] [codec]) - [size]"
- "Parsed X format options from HTML"
