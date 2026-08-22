use strict;
use warnings;
use Test::More tests => 12;

for my $property (
    'utf8::perl x',
    'Foo::bar',
    'Foo::9bar',
) {
    my $source = 'qr/\\p{' . $property . '}/';
    my $ok = eval "$source; 1";
    ok(!$ok, "$property is rejected");
    like($@, qr/^Illegal user-defined property name \"\Q$property\E\" in regex/,
        "$property reports the Perl user-property diagnostic");
    like($@, qr/\\p\{\Q$property\E\} <-- HERE /,
        "$property marks immediately after the property");
    unlike($@, qr/^Can't find Unicode property definition/,
        "$property is not reported as an unknown built-in property");
}
