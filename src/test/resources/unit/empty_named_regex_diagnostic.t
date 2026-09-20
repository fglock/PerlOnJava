use strict;
use warnings;
use Test::More;

if ($^X !~ m{/jperl-exec$}) {
    plan skip_all => 'system Perl 5.42 crashes on the upstream GH #16930 reproducer';
}

eval q{qr/(?{})\N{}/;while(my($0)=0){}};

like($@,
    qr/^Unknown charname '' at \(eval 1\) line 1, near "\{\}\)"\n$/,
    'empty named character in a regex keeps the token excerpt');
unlike($@, qr/within pattern|Empty \\N\{\}/,
    'empty named character reports one Perl diagnostic');

done_testing;
