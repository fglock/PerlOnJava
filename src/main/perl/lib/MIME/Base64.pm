package MIME::Base64;

use XSLoader;
XSLoader::load( 'MIME::Base64' );

*encode = \&encode_base64;
*decode = \&decode_base64;

1;