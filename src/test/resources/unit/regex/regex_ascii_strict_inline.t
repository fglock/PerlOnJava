use strict;
use warnings;
use utf8;
use Test::More tests => 8;

ok("s" !~ /(?iaa:\x{17f})/, 'inline /aa blocks long-s pattern crossing');
ok("\x{17f}" !~ /(?iaa:s)/, 'inline /aa blocks long-s input crossing');
ok("k" !~ /(?iaa:\x{212a})/, 'inline /aa blocks Kelvin pattern crossing');
ok("\x{212a}" !~ /(?iaa:k)/, 'inline /aa blocks Kelvin input crossing');
ok("\x{df}" !~ /(?iaa:ss)/, 'inline /aa blocks multi-character folds');
ok("Ä" =~ /(?iaa:ä)/, 'inline /aa keeps non-ASCII folds');
ok("\x{17f}" =~ /(?iu:s)/aa, 'inline /u can relax an outer /aa');
ok("\x{17f}s\x{17f}" =~ /(?i:s)(?iaa:s)(?i:s)/u,
    'inline /aa restores the surrounding fold policy');

