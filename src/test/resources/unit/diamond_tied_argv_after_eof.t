use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

{
    package Local::DiamondARGVHandle;

    sub TIEHANDLE {
        my ($class, @lines) = @_;
        return bless { lines => \@lines }, $class;
    }

    sub READLINE {
        my ($self) = @_;
        return shift @{$self->{lines}} unless wantarray;
        return () unless @{$self->{lines}};
        my @lines = @{$self->{lines}};
        $self->{lines} = [];
        return @lines;
    }
}

my ($fh, $path) = tempfile();
print {$fh} "initial input\n";
close $fh or die "cannot close temporary input: $!";

@ARGV = ($path);
while (<>) { }

tie *ARGV, 'Local::DiamondARGVHandle', "scalar value\n", "list value 1\n", "list value 2\n";

is scalar(<>), "scalar value\n",
    'diamond scalar read uses ARGV tied after the previous traversal reached EOF';
is_deeply [<>], ["list value 1\n", "list value 2\n"],
    'diamond list read continues using the newly tied ARGV handle';
ok !defined(scalar <>), 'diamond reports EOF after tied ARGV values are exhausted';

unlink $path or die "cannot remove temporary input: $!";
done_testing;
