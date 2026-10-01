use strict;
use warnings;
use Test::More tests => 5;

open my $fh, '<', $0 or die "open $0: $!";
my @cases = (
    [ readdir  => sub { readdir($_[0]) } ],
    [ telldir  => sub { telldir($_[0]) } ],
    [ seekdir  => sub { seekdir($_[0], 0) } ],
    [ rewinddir => sub { rewinddir($_[0]) } ],
    [ closedir => sub { closedir($_[0]) } ],
);

for my $case (@cases) {
    my ($operation, $invoke) = @$case;
    my $warning = '';
    {
        local $SIG{__WARN__} = sub { $warning .= shift };
        $invoke->($fh);
    }
    like($warning, qr/\Q$operation\E\(\) attempted on handle \$fh opened with open(?:\(\))?/,
         "$operation warns when given a filehandle opened with open");
}

close $fh;
