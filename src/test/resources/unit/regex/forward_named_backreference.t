use strict;
use warnings;
use Test::More tests => 4;

my $brace = eval q{qr/(?<before>\g{later})(?<later>x)(?&before)/};
ok(defined($brace) && !$@,
   'forward g-brace named backreference compiles before its capture');

my $angle = eval q{qr/(?<before>\k<later>)(?<later>x)(?&before)/};
ok(defined($angle) && !$@,
   'forward k-angle named backreference compiles before its capture');

my $missing = eval q{qr/(?<before>\g{missing})(?<later>x)/};
ok(!defined($missing) && $@ =~ /Reference to nonexistent named group/,
   'a named backreference missing from the complete pattern is rejected');

ok('xx' !~ $brace,
   'an unset forward backreference does not consume a later capture');
