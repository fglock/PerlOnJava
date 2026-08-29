use strict;
use warnings;

use File::Copy qw(move);
use File::Temp ();
use Test::More tests => 5;

{
    package Local::Path;

    use overload '""' => sub { ${$_[0]} }, fallback => 1;

    sub new {
        my $class = shift;
        my $value = $_[0];
        return bless \$value, ref($class) || $class;
    }

    sub tempfile { __PACKAGE__->new(File::Temp->new(@_)) }

    sub move_to {
        my ($self, $to) = @_;
        File::Copy::move($$self, $to) or die "move: $!";
        return $self->new($to);
    }

    sub spew {
        my ($self, $content) = @_;
        open my $file, '>', $$self or die "open: $!";
        $file->syswrite($content);
        return $self;
    }

    sub slurp {
        my ($self) = @_;
        open my $file, '<', $$self or die "open: $!";
        my $ret = my $content = '';
        while ($ret = $file->sysread(my $buffer, 131072, 0)) { $content .= $buffer }
        return $content;
    }
}

my $directory = File::Temp::tempdir(CLEANUP => 1);
my $file = Local::Path::tempfile(DIR => $directory);
$file->spew('works');
is($file->slurp, 'works', 'right content');

my $target = Local::Path::tempfile(DIR => $directory);
$file->move_to($target);
ok(-e $target, 'target exists');
ok(!-e $file, 'source is gone');

undef $file;
is($target->slurp, 'works', 'moved content remains readable');
ok(-e $target, 'target remains owned after method call');
