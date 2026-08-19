use strict;
use warnings;
use Test::More tests => 7;

unlike("x\c_y", qr/x\c\_y/, '\\c\\ leaves underscore outside control escape');
like("x\c\_y", qr/x\c\_y/, '\\c\\ matches control-backslash plus underscore');

for my $suffix ("z", "\0", "!", chr(254), chr(256)) {
    my $target = "a" . chr(0x1c) . $suffix;
    my $pattern = "a\\c\\$suffix";
    ok(eval { $target =~ /$pattern/ },
        sprintf('control-backslash preserves following U+%04X', ord($suffix)));
}
