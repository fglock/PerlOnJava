#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

my @cases = (
    [ q{qr/\p{gc=:L*:}/},
      q{Use of quantifier '*' is not allowed in Unicode property wildcard subpatterns},
      q{L* <-- HERE} ],
    [ q{qr/\p{gc=:L\G:}/},
      q{Use of '\G' is not allowed in Unicode property wildcard subpatterns},
      q{L\G <-- HERE} ],
    [ q{qr/\p{gc=:(?g)L:}/},
      q{Use of modifier 'g' is not allowed in Unicode property wildcard subpatterns},
      q{(?g <-- HERE )L} ],
    [ q{qr/\p{gc=:(?a)L:}/},
      q{Use of modifier 'a' is not allowed in Unicode property wildcard subpatterns},
      q{(?a) <-- HERE L} ],
    [ q{qr/\p{gc=:(?u)L:}/},
      q{Use of modifier 'u' is not allowed in Unicode property wildcard subpatterns},
      q{(?u) <-- HERE L} ],
    [ q{qr/\p{gc=:(?d)L:}/},
      q{Use of modifier 'd' is not allowed in Unicode property wildcard subpatterns},
      q{(?d) <-- HERE L} ],
    [ q{qr/\p{gc=:(?l)L:}/},
      q{Use of modifier 'l' is not allowed in Unicode property wildcard subpatterns},
      q{(?l) <-- HERE L} ],
    [ q{qr/\p{gc=:(?-m)L:}/},
      q{Use of modifier '-m' is not allowed in Unicode property wildcard subpatterns},
      q{(?-m <-- HERE )L} ],
    [ q{qr/\p{gc=:\pS:}/},
      q{Use of '\pS' is not allowed in Unicode property wildcard subpatterns},
      q{\pS <-- HERE} ],
    [ q{qr/\p{gc=:\PS:}/},
      q{Use of '\PS' is not allowed in Unicode property wildcard subpatterns},
      q{\PS <-- HERE} ],
);

for my $case (@cases) {
    my ($source, $message, $marked_source) = @$case;
    local $@;
    eval "no warnings 'experimental'; $source";
    ok($@ ne '', "$source is rejected");
    like($@, qr/^\Q$message\E/, "$source reports the Perl diagnostic");
    like($@, qr/\Q$marked_source\E/, "$source marks the Perl source position");
}

done_testing;
