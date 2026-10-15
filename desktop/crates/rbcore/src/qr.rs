//! QR codes — generate (URL sharing) + decode (from an image file, the
//! desktop adaptation of Android's camera scanner, via the quirc decoder).

use qrcode::QrCode;

/// PNG bytes of the QR for `text` at the given module scale, with the
/// four-module quiet zone the spec (and every decoder) requires.
pub fn png(text: &str, scale: usize) -> crate::CoreResult<Vec<u8>> {
    let code = QrCode::new(text.as_bytes()).map_err(|e| format!("qr encode: {e}"))?;
    let mut png_out = Vec::new();
    {
        use image::GenericImageView as _;
        let quiet = 4 * scale;
        let (w, h) = (code.width() * scale + 2 * quiet, code.width() * scale + 2 * quiet);
        let mut img = image::RgbImage::from_pixel(w as u32, h as u32, image::Rgb([255, 255, 255]));
        for y in 0..code.width() {
            for x in 0..code.width() {
                if code[(x, y)] == qrcode::Color::Dark {
                    for dy in 0..scale {
                        for dx in 0..scale {
                            img.put_pixel(
                                (quiet + x * scale + dx) as u32,
                                (quiet + y * scale + dy) as u32,
                                image::Rgb([0, 0, 0]),
                            );
                        }
                    }
                }
            }
        }
        let mut encoder = image::codecs::png::PngEncoder::new(&mut png_out);
        encoder
            .encode(img.as_raw(), w as u32, h as u32, image::ColorType::Rgb8)
            .map_err(|e| format!("qr png: {e}"))?;
    }
    Ok(png_out)
}

/// Decodes a QR from PNG/JPEG image bytes.
pub fn decode(image_bytes: &[u8]) -> crate::CoreResult<String> {
    let img = image::load_from_memory(image_bytes).map_err(|e| format!("qr image: {e}"))?;
    let gray = img.to_luma8();
    let (w, h) = (gray.width() as usize, gray.height() as usize);
    let raw = gray.as_raw().clone();
    let mut quirc = quircs::Quirc::new();
    for code in quirc.identify(w, h, &raw) {
        // CodeIter yields Result<Code, ExtractError>; only well-formed codes
        // are worth decoding.
        if let Ok(code) = code {
            if let Ok(decoded) = code.decode() {
                let payload = decoded.payload;
                if !payload.is_empty() {
                    return Ok(String::from_utf8_lossy(&payload).into_owned());
                }
            }
        }
    }
    Err("no QR code found in the image".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_and_decode_roundtrip() {
        let data = "https://example.com/share/abc123";
        let bytes = png(data, 8).unwrap();
        // signature check
        assert_eq!(&bytes[..4], b"\x89PNG");
        // decode what we generated
        let back = decode(&bytes).unwrap();
        assert_eq!(back, data);
    }

    #[test]
    fn utf8_payload() {
        let bytes = png("otpauth://totp/alice?secret=JBSWY3DP", 4).unwrap();
        assert_eq!(decode(&bytes).unwrap(), "otpauth://totp/alice?secret=JBSWY3DP");
    }
}
