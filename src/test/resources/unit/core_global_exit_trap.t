use strict;
use warnings;
use Test::More tests => 3;

BEGIN {
    *CORE::GLOBAL::exit = sub (;$) { die "outer exit $_[0]\n" };
}

sub invoke_exit { exit $_[0] }

eval { invoke_exit(3) };
is($@, "outer exit 3\n", 'CORE::GLOBAL::exit overrides bare exit');

{
    local *CORE::GLOBAL::exit;
    *CORE::GLOBAL::exit = sub (;$) { die "trapped exit $_[0]\n" };
    eval { invoke_exit(7) };
    is($@, "trapped exit 7\n", 'localized CORE::GLOBAL::exit traps compiled exit');
}

eval { invoke_exit(5) };
is($@, "outer exit 5\n", 'localized exit override is restored');
