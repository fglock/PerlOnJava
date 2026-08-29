use strict;
use warnings;

use File::Temp qw(tempdir);
use Test::More;

{
    package Issue1115::Path;

    use overload '""' => sub { "${$_[0]}" }, fallback => 1;

    sub wrap {
        my ($class, $value) = @_;
        return bless \$value, $class;
    }
}

my $dir      = tempdir(CLEANUP => 1);
my $filename = "$dir/runtime.log";
my $path     = Issue1115::Path->wrap($filename);
my $wrapped  = Issue1115::Path->wrap($path);

is "$wrapped", $filename, 'nested overloaded scalar reference stringifies as a path';

ok open(my $append, '+>>', $$wrapped), 'open accepts overloaded scalar reference as a filename';
cmp_ok fileno($append), '>=', 0, 'overloaded filename opens a real file descriptor';
is syswrite($append, 'first'), 5, 'syswrite writes through the real filehandle';
ok close($append), 'append filehandle closes cleanly';
is "$path", $filename, 'writing does not replace the overloaded filename value';

ok open(my $selected, '>>', $$wrapped), 'selected output handle opens from overloaded filename';
my $previous = select($selected);
print ' second';
select($previous);
ok close($selected), 'selected output handle closes cleanly';
is "$path", $filename, 'selected output does not append data to the filename scalar';

my $read_path = Issue1115::Path->wrap($path);
ok open(my $read, '<', $$read_path), 'read handle opens from overloaded filename';
is do { local $/; <$read> }, 'first second', 'read handle returns file contents';
ok close($read), 'read filehandle closes cleanly';

done_testing;
