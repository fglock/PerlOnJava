package Issue1377::Levels;

use strict;
use warnings;
use Exporter 'import';

our @EXPORT = qw(INFO);
our $AUTOLOAD;
my %LEVEL = (INFO => 6);

sub AUTOLOAD {
    my $name = $AUTOLOAD;
    $name =~ s/.*:://;
    return if $name eq 'DESTROY';
    die "Unknown level $name" unless exists $LEVEL{$name};

    no strict 'refs';
    *{$AUTOLOAD} = sub { $LEVEL{$name} };
    goto &{$AUTOLOAD};
}

1;
