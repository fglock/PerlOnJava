use strict;
use warnings;

use File::Basename qw(dirname);
use File::Temp;
use Test::More tests => 5;

{
    package Local::TempPath;
    use overload '""' => sub { ${$_[0]} }, fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless \$value, ref($class) || $class;
    }

    sub dirname { $_[0]->new(File::Basename::dirname(${$_[0]})) }

    sub spew {
        my ($self, $content) = @_;
        open my $fh, '>', $$self or die "open $$self: $!";
        print {$fh} $content or die "print $$self: $!";
        close $fh or die "close $$self: $!";
        return $self;
    }

    sub spurt { shift->spew(join '', @_) }

    sub slurp {
        my $self = shift;
        open my $fh, '<', $$self or die "open $$self: $!";
        local $/;
        return <$fh>;
    }
}

my $directory = File::Temp::tempdir(CLEANUP => 1);
my $file = Local::TempPath->new(File::Temp->new(DIR => $directory));
my $path = "$file";

ok(-f $path, 'wrapped temporary file exists');
is("" . $file->dirname, $directory, 'temporary file has expected parent');
is($file->spew('test')->slurp, 'test', 'method chain preserves temporary file');
is($file->spurt('just', 'a', 'test')->slurp, 'justatest',
    'second method chain preserves temporary file');
undef $file;
ok(!-e $path, 'discarding path wrapper unlinks temporary file');
