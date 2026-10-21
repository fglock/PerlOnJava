use strict;
use warnings;

sub _new_perl { return 2 }

*_convert = eval <<'EOT' || \&_new_perl;
sub { return 1 }
EOT

print "1..1\n";
print _convert() == 1
    ? "ok 1 - heredoc eval with coderef fallback installs the generated subroutine\n"
    : "not ok 1 - heredoc eval with coderef fallback installs the generated subroutine\n";
