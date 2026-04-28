use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 3;

SKIP: {
    skip 'fix this next release', 3;
    my $x;
    is(Dump(bless(\$x)), 'foo');
}

__END__
03:14 < audreyt> ingy:
03:14 < audreyt> use YAML; my $x; print Dump bless(\$x);
03:14 < audreyt> is erroneous
03:14 < audreyt> then
03:14 < audreyt> use YAML; my $x = \3; print Dump bless(\$x);
03:14 < audreyt> is fatal error
03:15 < audreyt> use YAML; my $x; $x = \$x; print Dump bless(\$x);
03:15 < audreyt> is scary fatal error
03:15 < audreyt> (YAML::Syck handles all three ^^;)
03:16  * audreyt goes back to do $job work

